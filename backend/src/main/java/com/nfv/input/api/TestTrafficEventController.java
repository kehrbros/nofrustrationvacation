package com.nfv.input.api;

import com.nfv.incidents.logic.IncidentLocation;
import com.nfv.incidents.logic.RoutePlacement;
import com.nfv.input.dto.TrafficEventStatusDto;
import com.nfv.input.logic.TrafficEventInspection;
import com.nfv.input.logic.TrafficEventProcessor;
import com.nfv.input.logic.TrafficPolling;
import com.nfv.input.sources.manual.ManualSource;
import com.nfv.input.sources.manual.ManualTrafficEvent;
import io.swagger.v3.oas.annotations.Operation;
import com.nfv.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test tool: enter traffic events by hand and see what became of them, see Docs/traffic-feed.md#testquelle-manual.
 * Only exists with {@code nfv.input.sources.manual.enabled=true} and needs the role {@code tester}
 * ({@code ApiSecurityConfig}), because an event affects the routes of every user.
 */
@Tag(name = "Test: traffic events", description = "Test tool, role tester, off unless enabled")
@RestController
@RequestMapping("/api/v1/test/traffic-events")
@ConditionalOnProperty(name = "nfv.input.sources.manual.enabled", havingValue = "true")
@RequiredArgsConstructor
public class TestTrafficEventController {
    private final ManualSource manualSource;
    private final TrafficPolling polling;
    private final TrafficEventProcessor processor;
    private final TrafficEventInspection inspection;
    private final RoutePlacement routePlacement;

    /** A spot tapped next to a route of the caller. */
    public record PlacementRequest(long tripId, long routeId, ManualTrafficEvent.Position point) {
    }

    @Operation(summary = "Report a traffic event", description = "Goes through the normal flow and answers with the "
            + "result. Posting the same id again changes the event, resolved ends it. With onRoute the point is "
            + "placed onto that route first.")
    @PostMapping
    public ResponseEntity<?> report(@AuthenticationPrincipal CurrentUser user, @RequestBody ManualTrafficEvent event) {
        String problem = event.problem();
        if (problem != null) {
            return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, problem));
        }
        RoutePlacement.Placement placement = null;
        if (event.onRoute() != null) {
            placement = place(user, event.onRoute().tripId(), event.onRoute().routeId(), event.point());
            if (placement == null) {
                return notOnRoute(event.onRoute().routeId());
            }
            event = event.at(new ManualTrafficEvent.Position(placement.point().lat(), placement.point().lon()));
        }
        manualSource.add(event);
        TrafficPolling.PollResult result = polling.pollNow(ManualSource.NAME);
        if (result.status() != TrafficPolling.Status.POLLED) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ProblemDetail.forStatusAndDetail(
                    HttpStatus.CONFLICT, "Source " + ManualSource.NAME + ": " + result.status()));
        }
        processor.processPending();
        RoutePlacement.Placement placed = placement;
        String id = event.id();
        return inspection.latest(ManualSource.NAME, id).map(status -> TrafficEventStatusDto.of(status, placed))
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new IllegalStateException("Event " + id + " was not stored"));
    }

    @Operation(summary = "Place a spot onto a route", description = "Nearest point of a planned route of the caller, "
            + "with the road there and the planned passage; 422 if the route is unknown, not planned or the spot is "
            + "farther than 5 km away. Nothing is reported.")
    @PostMapping("/placement")
    public ResponseEntity<?> placement(@AuthenticationPrincipal CurrentUser user,
                                       @RequestBody PlacementRequest request) {
        if (request.point() == null) {
            return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "point is required"));
        }
        RoutePlacement.Placement placement = place(user, request.tripId(), request.routeId(), request.point());
        return placement == null ? notOnRoute(request.routeId())
                : ResponseEntity.ok(TrafficEventStatusDto.PlacementDto.of(placement));
    }

    private RoutePlacement.Placement place(CurrentUser user, long tripId, long routeId,
                                           ManualTrafficEvent.Position point) {
        return routePlacement.place(user.appUserId(), tripId, routeId,
                new IncidentLocation.Coordinate(point.lat(), point.lon())).orElse(null);
    }

    private static ResponseEntity<ProblemDetail> notOnRoute(long routeId) {
        return ResponseEntity.unprocessableEntity().body(ProblemDetail.forStatusAndDetail(
                HttpStatus.valueOf(422),
                "Route " + routeId + " is unknown, not planned or farther than "
                        + Math.round(RoutePlacement.MAX_DISTANCE_METERS / 1000) + " km away"));
    }

    @Operation(summary = "What became of a traffic event", description = "Latest version of the event of any source, "
            + "e.g. source=nz-nzta and the id of NZTA.")
    @GetMapping("/{id}")
    public ResponseEntity<TrafficEventStatusDto> status(@PathVariable String id,
                                                        @RequestParam(defaultValue = ManualSource.NAME) String source) {
        return inspection.latest(source, id).map(TrafficEventStatusDto::of).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Unreadable request: " + exception.getMostSpecificCause().getMessage()));
    }
}
