package com.nfv.incidents.api;

import com.nfv.incidents.dto.TripIncidentCountDto;
import com.nfv.incidents.dto.TripIncidentDto;
import com.nfv.incidents.entities.Consequence;
import com.nfv.incidents.entities.Incident;
import com.nfv.incidents.logic.TripIncidents;
import com.nfv.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Incidents", description = "Incidents affecting the trips of the authenticated user")
@RestController
@RequiredArgsConstructor
public class TripIncidentController {
    private final TripIncidents tripIncidents;

    @Operation(summary = "Current incidents of a trip", description = "Incidents not ended yet, ascending by start.")
    @GetMapping("/api/v1/trips/{tripId}/incidents")
    public List<TripIncidentDto> getTripIncidents(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId) {
        return tripIncidents.active(user.appUserId(), tripId).stream().map(TripIncidentController::toDto).toList();
    }

    @Operation(summary = "Number of current incidents per trip", description = "Only trips with at least one incident.")
    @GetMapping("/api/v1/incidents/trip-counts")
    public List<TripIncidentCountDto> getTripIncidentCounts(@AuthenticationPrincipal CurrentUser user) {
        return tripIncidents.activeCountsByTrip(user.appUserId()).entrySet().stream()
                .map(entry -> new TripIncidentCountDto(entry.getKey(), entry.getValue())).toList();
    }

    private static TripIncidentDto toDto(Consequence consequence) {
        Incident incident = consequence.getIncident();
        return new TripIncidentDto(incident.getId(), consequence.getRouteId(), incident.getType(),
                incident.getDescription(), consequence.getImpact(),
                consequence.getDelay() == null ? null : consequence.getDelay().toMinutes(), incident.getValidFrom(),
                incident.getValidTo(), consequence.getDetectedAt(), consequence.getUpdatedAt());
    }
}
