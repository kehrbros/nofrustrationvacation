package com.nfv.incidents.api;

import com.nfv.incidents.dto.RouteIncidentSummaryDto;
import com.nfv.incidents.dto.TripIncidentCountDto;
import com.nfv.incidents.dto.TripIncidentDto;
import com.nfv.incidents.entities.Consequence;
import com.nfv.incidents.entities.Incident;
import com.nfv.incidents.logic.RouteIncidentSummary;
import com.nfv.incidents.logic.TripIncidents;
import com.nfv.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

@Tag(name = "Incidents", description = "Incidents affecting the trips of the authenticated user")
@RestController
@RequiredArgsConstructor
public class TripIncidentController {
    private final TripIncidents tripIncidents;

    @Operation(summary = "Current incidents of a trip", description = "Incidents not ended yet, ranked: TOO_LATE by "
            + "lateness (not passable first), DELAYED by own delay, NO_IMPACT by passage; ties by earlier passage.")
    @GetMapping("/api/v1/trips/{tripId}/incidents")
    public List<TripIncidentDto> getTripIncidents(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId) {
        return tripIncidents.active(user.appUserId(), tripId).stream().map(TripIncidentController::toDto).toList();
    }

    @Operation(summary = "Situation of the routes of a trip", description = "Per planned route: worst category of "
            + "its incidents, expected arrival and slack against the arrival at the target stop.")
    @GetMapping("/api/v1/trips/{tripId}/incidents/routes")
    public List<RouteIncidentSummaryDto> getRouteSummaries(@AuthenticationPrincipal CurrentUser user,
                                                           @PathVariable long tripId) {
        return tripIncidents.routeSummaries(user.appUserId(), tripId).stream()
                .map(TripIncidentController::toDto).toList();
    }

    @Operation(summary = "Number of current incidents per trip", description = "Only TOO_LATE and DELAYED, only "
            + "trips with at least one.")
    @GetMapping("/api/v1/incidents/trip-counts")
    public List<TripIncidentCountDto> getTripIncidentCounts(@AuthenticationPrincipal CurrentUser user) {
        return tripIncidents.activeCountsByTrip(user.appUserId()).entrySet().stream()
                .map(entry -> new TripIncidentCountDto(entry.getKey(), entry.getValue())).toList();
    }

    private static TripIncidentDto toDto(Consequence consequence) {
        Incident incident = consequence.getIncident();
        return new TripIncidentDto(incident.getId(), consequence.getRouteId(), incident.getType(),
                incident.getDescription(), incident.impact(), minutes(consequence.getDelay()),
                incident.getValidFrom(), incident.getValidTo(), consequence.getDetectedAt(), consequence.getUpdatedAt(),
                consequence.getCategory(), consequence.getMatch(), minutes(consequence.getAssessedDelay()),
                consequence.isDelayAssumed(), consequence.getPassageAt(), consequence.getExpectedArrival(),
                consequence.getDeadline(), consequence.getWindowStart(), consequence.getWindowEnd(),
                incident.isTimesUnconfirmed());
    }

    private static RouteIncidentSummaryDto toDto(RouteIncidentSummary summary) {
        return new RouteIncidentSummaryDto(summary.routeId(), summary.worstCategory(), summary.plannedArrival(),
                summary.expectedArrival(), summary.deadline(), minutes(summary.slack()), summary.unpassable(),
                summary.plannedTooLate());
    }

    private static @Nullable Long minutes(@Nullable Duration duration) {
        return duration == null ? null : duration.toMinutes();
    }
}
