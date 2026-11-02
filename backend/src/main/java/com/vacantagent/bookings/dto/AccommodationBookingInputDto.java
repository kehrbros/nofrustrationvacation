package com.vacantagent.bookings.dto;

import com.vacantagent.bookings.entities.BookingSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

@Schema(description = "Accommodation booking to create or replace. Check-in, check-out and cancellation deadline are "
        + "local times at the accommodation without offset; the backend determines the time zone from the coordinates "
        + "and uses fallbackTimeZone (the user's IANA zone) only without coordinates.")
public record AccommodationBookingInputDto(
        @Schema(description = "How the booking was entered, default MANUAL") @Nullable BookingSource source,
        String name,
        @Nullable String addressLine,
        @Nullable String countryCode,
        @Nullable Double latitude,
        @Nullable Double longitude,
        @Nullable String placeRef,
        @Nullable String phone,
        @Nullable String email,
        @Nullable String website,
        @Nullable String provider,
        @Schema(description = "Booking number, never the PIN") @Nullable String reference,
        @Nullable String manageUrl,
        @Schema(example = "2027-07-12T15:00:00") LocalDateTime checkIn,
        @Schema(example = "2027-07-15T10:00:00") LocalDateTime checkOut,
        @Schema(example = "Europe/Berlin") @Nullable String fallbackTimeZone,
        @Nullable LocalTime checkInFrom,
        @Nullable LocalTime checkInUntil,
        @Nullable LocalTime checkOutUntil,
        @Nullable Integer guests,
        @Nullable Integer rooms,
        @Schema(type = "string", format = "decimal", example = "412.50") @Nullable BigDecimal price,
        @Nullable String currency,
        @Nullable LocalDateTime cancellationDeadline,
        @Nullable String notes) {
}
