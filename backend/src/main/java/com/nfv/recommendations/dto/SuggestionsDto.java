package com.nfv.recommendations.dto;

import com.nfv.recommendations.logic.SuggestionProblem;
import com.nfv.recommendations.logic.WaypointMode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "Route suggestions of a route, computed per request and not stored, or why there are none "
        + "(problem)")
public record SuggestionsDto(
        long routeId,
        @Schema(description = "null if there is a problem") @Nullable WaypointMode waypointMode,
        @Schema(description = "Departure the suggestions were computed from, null if there is a problem")
        @Nullable OffsetDateTime departureAt,
        @Schema(description = "Best first, empty if there is a problem") List<RouteOptionDto> options,
        @Schema(description = "The planned route for comparison, null if not planned") @Nullable
        ReferenceRouteDto reference,
        @Schema(description = "MISSING_COORDINATES, MISSING_DEPARTURE or NO_ROUTE, null if there are suggestions")
        @Nullable SuggestionProblem problem) {
}
