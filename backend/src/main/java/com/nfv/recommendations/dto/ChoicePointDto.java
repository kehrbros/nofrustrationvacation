package com.nfv.recommendations.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

@Schema(description = "Point of a route suggestion that becomes a waypoint when the suggestion is chosen")
public record ChoicePointDto(
        double latitude,
        double longitude,
        @Schema(description = "Road the point should snap to, e.g. SH 73") @Nullable String roadHint,
        @Schema(description = "Existing waypoint of the user that stays (includeWaypoints), null: new point")
        @Nullable Long waypointId) {
}
