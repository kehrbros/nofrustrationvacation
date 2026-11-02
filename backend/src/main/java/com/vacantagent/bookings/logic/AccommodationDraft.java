package com.vacantagent.bookings.logic;

import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Accommodation booking read from a confirmation, not stored: the user checks and completes it, then saves it as
 * {@link NewAccommodationBooking}. Times are local at the accommodation. {@code uncertainFields} names fields the
 * user should look at.
 */
public record AccommodationDraft(
        boolean found,
        @Nullable String name,
        @Nullable String addressLine,
        @Nullable String countryCode,
        @Nullable String phone,
        @Nullable String email,
        @Nullable String website,
        @Nullable String provider,
        @Nullable String reference,
        @Nullable String manageUrl,
        @Nullable LocalDate checkInDate,
        @Nullable LocalTime checkInTime,
        @Nullable LocalDate checkOutDate,
        @Nullable LocalTime checkOutTime,
        @Nullable LocalTime checkInFrom,
        @Nullable LocalTime checkInUntil,
        @Nullable LocalTime checkOutUntil,
        @Nullable Integer guests,
        @Nullable Integer rooms,
        @Nullable BigDecimal price,
        @Nullable String currency,
        @Nullable LocalDateTime cancellationDeadline,
        List<String> uncertainFields) {

    static AccommodationDraft notFound() {
        return new AccommodationDraft(false, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, List.of());
    }
}
