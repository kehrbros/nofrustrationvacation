package com.nfv.trips.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.util.List;

@Schema(description = "Planned way of a route as a line for the map")
public record RouteLineDto(
        long routeId,
        @Schema(description = "Points in WGS84 in driving order") List<LinePointDto> geometry,
        double distanceMeters,
        @Schema(description = "Expected driving time in seconds") long durationSeconds,
        @Schema(description = "Roads of the chosen suggestion, e.g. SH 2, null if the route has none") @Nullable
        String via) {

    @Schema(description = "Point in WGS84")
    public record LinePointDto(double latitude, double longitude) {
    }
}
