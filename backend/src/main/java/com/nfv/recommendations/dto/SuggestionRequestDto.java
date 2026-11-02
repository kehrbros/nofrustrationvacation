package com.nfv.recommendations.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

@Schema(description = "Request for route suggestions when planning")
public record SuggestionRequestDto(
        @Schema(description = "Suggestions wanted, 1 to 5, default 3") @Nullable Integer count,
        @Schema(description = "true: through the waypoints of the route, default false: only between the stops, "
                + "applying a suggestion then removes the waypoints") @Nullable Boolean includeWaypoints,
        @Schema(description = "Departure to try instead of the one at the start stop, not stored at the stop")
        @Nullable OffsetDateTime departureAt) {
}