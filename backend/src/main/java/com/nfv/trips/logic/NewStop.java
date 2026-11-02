package com.nfv.trips.logic;

import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

/** Stop to append to a trip. */
public record NewStop(String name, @Nullable Double latitude, @Nullable Double longitude,
                      @Nullable OffsetDateTime arrivalAt, @Nullable OffsetDateTime departureAt) {
}
