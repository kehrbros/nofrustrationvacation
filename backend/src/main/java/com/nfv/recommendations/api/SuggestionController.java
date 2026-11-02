package com.nfv.recommendations.api;

import com.nfv.recommendations.dto.AppliedSuggestionDto;
import com.nfv.recommendations.dto.ConflictDto;
import com.nfv.recommendations.dto.GeoPointDto;
import com.nfv.recommendations.dto.ReferenceRouteDto;
import com.nfv.recommendations.dto.RouteOptionDto;
import com.nfv.recommendations.dto.SuggestionRequestDto;
import com.nfv.recommendations.dto.SuggestionSetDto;
import com.nfv.recommendations.entities.RouteOption;
import com.nfv.recommendations.entities.SuggestionSet;
import com.nfv.recommendations.logic.SuggestionApplication;
import com.nfv.recommendations.logic.SuggestionResult;
import com.nfv.recommendations.logic.Suggestions;
import com.nfv.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

@Tag(name = "Suggestions", description = "Route suggestions when planning, see Docs/recommendations.md")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SuggestionController {
    private final Suggestions suggestions;
    private final SuggestionApplication application;
    private final Clock clock;

    @Operation(summary = "Suggestions for a route", description = "Reuses the current set if nothing changed since. "
            + "Without departure or coordinates there is no set, see problem.")
    @PostMapping("/routes/{routeId}/suggestions")
    public SuggestionSetDto suggest(@AuthenticationPrincipal CurrentUser user, @PathVariable long routeId,
                                    @RequestBody(required = false) @Nullable SuggestionRequestDto request) {
        SuggestionRequestDto values = request == null ? new SuggestionRequestDto(null, null, null) : request;
        return toDto(routeId, suggestions.forPlanning(user.appUserId(), routeId, values.count(),
                Boolean.TRUE.equals(values.includeWaypoints()), values.departureAt()));
    }

    @Operation(summary = "A set of suggestions", description = "With the planned route as of now for comparison.")
    @GetMapping("/suggestion-sets/{setId}")
    public SuggestionSetDto get(@AuthenticationPrincipal CurrentUser user, @PathVariable long setId) {
        SuggestionResult result = suggestions.get(user.appUserId(), setId);
        return toDto(result.set().getRouteId(), result);
    }

    @Operation(summary = "Apply a suggestion", description = "Its via points replace the waypoints of the route, "
            + "which is planned again. 409 SUGGESTION_STALE if the route changed since.")
    @PostMapping("/suggestion-sets/{setId}/options/{optionId}/apply")
    public AppliedSuggestionDto apply(@AuthenticationPrincipal CurrentUser user, @PathVariable long setId,
                                      @PathVariable long optionId) {
        SuggestionApplication.Applied applied = application.apply(user.appUserId(), setId, optionId);
        return new AppliedSuggestionDto(applied.tripId(), applied.routeId(),
                applied.result() == null ? null : applied.result().outcome(),
                applied.result() == null ? null : applied.result().reason());
    }

    private SuggestionSetDto toDto(long routeId, SuggestionResult result) {
        SuggestionResult.Reference ref = result.reference();
        ReferenceRouteDto reference = ref == null ? null : new ReferenceRouteDto(ref.departureAt(), ref.arrivalAt(),
                ref.distanceMeters(), ref.duration().toSeconds(), ref.delay().toSeconds(), ref.blocked(),
                conflicts(ref.conflicts()));
        SuggestionSet set = result.set();
        if (set == null) {
            return new SuggestionSetDto(null, routeId, null, null, null, null, List.of(), reference,
                    result.problem());
        }
        return new SuggestionSetDto(set.getId(), set.getRouteId(), set.statusAt(OffsetDateTime.now(clock)),
                set.getWaypointMode(), set.getDepartureAt(), set.getExpiresAt(),
                set.getOptions().stream().map(SuggestionController::option).toList(), reference, null);
    }

    private static RouteOptionDto option(RouteOption option) {
        return new RouteOptionDto(option.getId(), option.getRank(), option.getKind(),
                option.getLabels().stream().sorted().toList(), option.getVia(), option.getDepartureAt(),
                option.getArrivalAt(), option.getDistanceMeters(), option.getDuration().toSeconds(),
                option.getWaiting().toSeconds(), option.getDelay().toSeconds(), option.getSharedRatio(),
                Arrays.stream(option.getGeometry().getCoordinates())
                        .map(c -> new GeoPointDto(c.getY(), c.getX())).toList(),
                option.getPoints().stream().map(p -> new GeoPointDto(p.getLatitude(), p.getLongitude())).toList(),
                option.getConflicts().stream().map(c -> new ConflictDto(c.getIncidentId(), c.getImpact(),
                        c.getDelay() == null ? null : c.getDelay().toSeconds())).toList(),
                option.getAppliedAt());
    }

    private static List<ConflictDto> conflicts(List<RouteOption.Conflict> conflicts) {
        return conflicts.stream().map(c -> new ConflictDto(c.incidentId(), c.impact(),
                c.delay() == null ? null : c.delay().toSeconds())).toList();
    }
}