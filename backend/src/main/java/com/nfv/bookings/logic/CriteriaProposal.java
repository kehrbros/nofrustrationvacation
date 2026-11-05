package com.nfv.bookings.logic;

import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Criteria filled from an accommodation booking for the user to check, see Docs/accommodation-alternatives.md.
 *
 * @param missing fields the booking does not provide and the user has to enter: {@code location}, {@code checkIn},
 *                {@code checkOut}, {@code guests}
 */
public record CriteriaProposal(@Nullable Double latitude, @Nullable Double longitude, @Nullable String placeName,
                               int radiusMeters, @Nullable LocalDate checkIn, @Nullable LocalDate checkOut,
                               @Nullable Integer guests, int rooms, @Nullable BigDecimal maxPrice, String currency,
                               @Nullable String excludeHotelId, List<String> missing) {
}
