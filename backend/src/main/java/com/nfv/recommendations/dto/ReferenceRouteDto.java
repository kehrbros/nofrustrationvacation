package com.nfv.recommendations.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "The planned route evaluated like the suggestions, e.g. \"as planned (+45 min)\"")
public record ReferenceRouteDto(
        OffsetDateTime departureAt,
        @Schema(description = "Arrival including delays") OffsetDateTime arrivalAt,
        double distanceMeters,
        long durationSeconds,
        long delaySeconds,
        @Schema(description = "Passes a closure during its validity") boolean blocked,
        List<ConflictDto> conflicts) {
}