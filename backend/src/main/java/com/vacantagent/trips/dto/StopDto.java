package com.vacantagent.trips.dto;

import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

public record StopDto(long id, int position, String name, @Nullable Double latitude, @Nullable Double longitude,
                      @Nullable OffsetDateTime arrivalAt, @Nullable OffsetDateTime departureAt) {
}
