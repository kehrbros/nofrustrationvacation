package com.vacantagent.bookings.logic;

import com.vacantagent.bookings.entities.BookingSource;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Accommodation booking as entered or confirmed by the user. Check-in, check-out and cancellation deadline are local
 * times at the accommodation; {@code fallbackTimeZone} (the user's zone) applies only if the place has no coordinates.
 */
public record NewAccommodationBooking(
        BookingSource source,
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
        @Nullable String reference,
        @Nullable String manageUrl,
        LocalDateTime checkIn,
        LocalDateTime checkOut,
        @Nullable String fallbackTimeZone,
        @Nullable LocalTime checkInFrom,
        @Nullable LocalTime checkInUntil,
        @Nullable LocalTime checkOutUntil,
        @Nullable Integer guests,
        @Nullable Integer rooms,
        @Nullable BigDecimal price,
        @Nullable String currency,
        @Nullable LocalDateTime cancellationDeadline,
        @Nullable String notes) {
}
