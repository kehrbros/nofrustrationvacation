package com.nfv.recommendations.dto;

import com.nfv.recommendations.entities.SuggestionStatus;
import com.nfv.recommendations.entities.WaypointMode;
import com.nfv.recommendations.logic.SuggestionProblem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "Route suggestions of a route, or why there are none (problem)")
public record SuggestionSetDto(
        @Schema(description = "null if there is a problem") @Nullable Long id,
        long routeId,
        @Nullable SuggestionStatus status,
        @Nullable WaypointMode waypointMode,
        @Nullable OffsetDateTime departureAt,
        @Nullable OffsetDateTime expiresAt,
        List<RouteOptionDto> options,
        @Schema(description = "The planned route for comparison, null if not planned") @Nullable
        ReferenceRouteDto reference,
        @Schema(description = "MISSING_COORDINATES, MISSING_DEPARTURE or NO_ROUTE, null if there are suggestions")
        @Nullable SuggestionProblem problem) {
}