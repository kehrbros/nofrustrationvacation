package com.vacantagent.bookings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.LocalTime;

@Schema(description = "Place and house rules of an accommodation, times local at the place")
public record AccommodationDto(String name, @Nullable String addressLine, @Nullable String countryCode,
                               @Nullable Double latitude, @Nullable Double longitude,
                               @Schema(description = "IANA time zone of the place, e.g. Europe/Rome") String timeZone,
                               @Nullable String phone, @Nullable String email, @Nullable String website,
                               @Nullable String placeRef, @Nullable LocalTime checkInFrom,
                               @Nullable LocalTime checkInUntil, @Nullable LocalTime checkOutUntil,
                               @Nullable Integer guests, @Nullable Integer rooms) {
}
