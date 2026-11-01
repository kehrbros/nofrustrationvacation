package com.vacantagent.trips.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

@Schema(description = "Waypoint in the ordered list of a route")
public record WaypointInputDto(
        @Schema(description = "Id of an existing waypoint to change, null for a new one") @Nullable Long id,
        double latitude, double longitude, @Nullable String name,
        @Schema(description = "Road the point should snap to, e.g. SH 73") @Nullable String roadHint) {
}