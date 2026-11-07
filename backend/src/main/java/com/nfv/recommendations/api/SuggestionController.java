package com.nfv.recommendations.api;

import com.nfv.recommendations.dto.ChoicePointDto;
import com.nfv.recommendations.dto.ChosenRouteDto;
import com.nfv.recommendations.dto.ConflictDto;
import com.nfv.recommendations.dto.GeoPointDto;
import com.nfv.recommendations.dto.ReferenceRouteDto;
import com.nfv.recommendations.dto.RouteChoiceDto;
import com.nfv.recommendations.dto.RouteOptionDto;
import com.nfv.recommendations.dto.SuggestionRequestDto;
import com.nfv.recommendations.dto.SuggestionsDto;
import com.nfv.recommendations.logic.RouteChoice;
import com.nfv.recommendations.logic.SuggestedOption;
import com.nfv.recommendations.logic.SuggestionResult;
import com.nfv.recommendations.logic.Suggestions;
import com.nfv.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Suggestions", description = "Route suggestions when planning, see Docs/recommendations.md")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SuggestionController {
    private final Suggestions suggestions;
    private final RouteChoice routeChoice;

    @Operation(summary = "Suggestions for a route", description = "Computed per request and not stored. Without "
            + "departure or coordinates there are none, see problem.")
    @PostMapping("/routes/{routeId}/suggestions")
    public SuggestionsDto suggest(@AuthenticationPrincipal CurrentUser user, @PathVariable long routeId,
                                  // no @Nullable here: springdoc would make the schema of the body type null
                                  @RequestBody(required = false) SuggestionRequestDto request) {
        SuggestionRequestDto values = request == null ? new SuggestionRequestDto(null, null, null) : request;
        SuggestionResult result = suggestions.forPlanning(user.appUserId(), routeId, values.count(),
                Boolean.TRUE.equals(values.includeWaypoints()), values.departureAt());
        SuggestionResult.Reference ref = result.reference();
        ReferenceRouteDto reference = ref == null ? null : new ReferenceRouteDto(ref.departureAt(), ref.arrivalAt(),
                ref.distanceMeters(), ref.duration().toSeconds(), ref.delay().toSeconds(), ref.blocked(),
                conflicts(ref.conflicts()));
        return new SuggestionsDto(routeId, result.waypointMode(), result.departureAt(),
                result.options().stream().map(SuggestionController::option).toList(), reference, result.problem());
    }

    @Operation(summary = "Choose a suggestion", description = "Its waypoints replace the waypoints of the route, "
            + "which is planned again. Waypoints with waypointId stay as they are. With departureAt only the "
            + "departure at the start stop changes.")
    @PutMapping("/routes/{routeId}/choice")
    public ChosenRouteDto choose(@AuthenticationPrincipal CurrentUser user, @PathVariable long routeId,
                                 @RequestBody RouteChoiceDto choice) {
        List<ChoicePointDto> waypoints = choice.waypoints() == null ? List.of() : choice.waypoints();
        RouteChoice.Chosen chosen = choice.departureAt() != null
                ? routeChoice.chooseDeparture(user.appUserId(), routeId, choice.departureAt())
                : routeChoice.choose(user.appUserId(), routeId, waypoints.stream()
                .map(p -> new SuggestedOption.Point(p.latitude(), p.longitude(), p.roadHint(), p.waypointId()))
                .toList());
        return new ChosenRouteDto(chosen.tripId(), chosen.routeId(),
                chosen.result() == null ? null : chosen.result().outcome(),
                chosen.result() == null ? null : chosen.result().reason());
    }

    private static RouteOptionDto option(SuggestedOption option) {
        return new RouteOptionDto(option.rank(), option.kind(), option.labels().stream().sorted().toList(),
                option.via(), option.departureAt(), option.arrivalAt(), option.distanceMeters(),
                option.duration().toSeconds(), option.waiting().toSeconds(), option.delay().toSeconds(),
                option.sharedRatio(),
                option.geometry().stream().map(p -> new GeoPointDto(p.lat(), p.lon())).toList(),
                option.points().stream().map(p -> new ChoicePointDto(p.latitude(), p.longitude(), p.roadHint(),
                        p.waypointId())).toList(),
                conflicts(option.conflicts()));
    }

    private static List<ConflictDto> conflicts(List<SuggestedOption.Conflict> conflicts) {
        return conflicts.stream().map(c -> new ConflictDto(c.incidentId(), c.impact(),
                c.delay() == null ? null : c.delay().toSeconds())).toList();
    }
}
