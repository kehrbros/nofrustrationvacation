package com.nfv.trips.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

@Schema(description = "All fields of a stop, replace the current ones")
public record UpdateStopDto(String name, @Nullable Double latitude, @Nullable Double longitude,
                            @Nullable OffsetDateTime arrivalAt, @Nullable OffsetDateTime departureAt) {
}