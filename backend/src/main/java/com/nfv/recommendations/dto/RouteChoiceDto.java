package com.nfv.recommendations.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "The chosen suggestion: its waypoints as returned with it, empty for the fastest way without "
        + "waypoints")
public record RouteChoiceDto(
        List<ChoicePointDto> waypoints,
        @Schema(description = "New departure at the start stop (suggestion of kind DEPARTURE_SHIFT): waypoints are "
                + "ignored and stay as they are") @Nullable OffsetDateTime departureAt) {
}
