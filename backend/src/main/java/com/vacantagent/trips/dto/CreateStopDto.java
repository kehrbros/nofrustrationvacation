package com.vacantagent.trips.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

@Schema(description = "Stop appended to the trip, points in time with the offset of the place or the user")
public record CreateStopDto(String name, @Nullable Double latitude, @Nullable Double longitude,
                            @Nullable OffsetDateTime arrivalAt, @Nullable OffsetDateTime departureAt) {
}
