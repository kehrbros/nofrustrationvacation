package com.nfv.bookings.logic;

import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What an alternative accommodation must offer, independent of the use case that fills it, see
 * Docs/accommodation-alternatives.md#criteria.
 *
 * @param maxPrice       total for the stay including our fee, in {@code currency}; null: no limit
 * @param excludeHotelId LiteAPI id of the current accommodation, if known
 * @param minStars       1 to 5, hotels without stars do not meet it; null: no limit
 */
public record AlternativeCriteria(double latitude, double longitude, int radiusMeters, LocalDate checkIn,
                                  LocalDate checkOut, int guests, int rooms, @Nullable BigDecimal maxPrice,
                                  String currency, @Nullable String excludeHotelId, @Nullable Integer minStars) {
}
