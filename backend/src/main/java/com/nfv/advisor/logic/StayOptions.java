package com.nfv.advisor.logic;

import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Accommodations when a route cannot be driven in time, see
 * Docs/advisor.md#später-unterkünfte-und-nachricht-an-die-unterkunft. Places on the route and the onward drive belong
 * to {@code recommendations} ({@code Stopovers}, planned). Not implemented yet, so no tool uses it.
 */
public interface StayOptions {
    List<StayOffer> search(long appUserId, StaySearch search);

    record StaySearch(double latitude, double longitude, int radiusMeters, LocalDate checkIn, LocalDate checkOut,
                      @Nullable BigDecimal maxPrice) {
    }

    /** With the position of the hotel, which {@code evaluate_stopover} routes to. */
    record StayOffer(String hotelId, String offerId, String name, double latitude, double longitude,
                     int distanceMeters, BigDecimal price, String currency, boolean refundable,
                     @Nullable OffsetDateTime freeCancellationUntil) {
    }
}
