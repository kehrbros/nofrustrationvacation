package com.nfv.recommendations.dto;

import com.nfv.trips.entities.PlanOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

@Schema(description = "Choice stored: waypoints replaced and the route planned again, read it via "
        + "GET /trips/{tripId}/routes/{routeId}")
public record ChosenRouteDto(
        long tripId,
        long routeId,
        @Schema(description = "Outcome of planning, the waypoints stay even if it failed") @Nullable
        PlanOutcome planOutcome,
        @Nullable String planReason) {
}
