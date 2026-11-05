package com.nfv.bookings.logic;

import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What an alternative accommodation must offer, independent of the use case that fills it, see
 * Docs/accommodation-alternatives.md#kriterien.
 *
 * @param maxPrice       total for the stay including our fee, in {@code currency}; null: no limit
 * @param excludeHotelId LiteAPI id of the current accommodation, if known
 */
public record AlternativeCriteria(double latitude, double longitude, int radiusMeters, LocalDate checkIn,
                                  LocalDate checkOut, int guests, int rooms, @Nullable BigDecimal maxPrice,
                                  String currency, @Nullable String excludeHotelId) {
}
