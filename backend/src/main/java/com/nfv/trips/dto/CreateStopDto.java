package com.nfv.trips.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

@Schema(description = "Stop added to the trip, points in time with the offset of the place or the user")
public record CreateStopDto(String name, @Nullable Double latitude, @Nullable Double longitude,
                            @Nullable OffsetDateTime arrivalAt, @Nullable OffsetDateTime departureAt,
                            @Schema(description = "Where to insert the stop, 0 to number of stops; null appends it")
                            @Nullable Integer position) {
}