package com.nfv.advisor.logic;

import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Accommodations when a route cannot be driven in time, see
 * Docs/advisor.md#später-unterkünfte-und-nachricht-an-die-unterkunft. Not implemented yet, so no tool uses it.
 */
public interface StayOptions {
    /** Places before the obstacle that can be reached by {@code latestArrival}. */
    List<OvernightArea> areasOnRoute(long appUserId, long routeId, OffsetDateTime latestArrival, int maxResults);

    List<StayOffer> search(long appUserId, StaySearch search);

    record OvernightArea(String name, double latitude, double longitude, OffsetDateTime arrivalAt,
                         double remainingMeters) {
    }

    record StaySearch(double latitude, double longitude, int radiusMeters, LocalDate checkIn, LocalDate checkOut,
                      @Nullable BigDecimal maxPrice) {
    }

    record StayOffer(String hotelId, String offerId, String name, int distanceMeters, BigDecimal price,
                     String currency, boolean refundable, @Nullable OffsetDateTime freeCancellationUntil) {
    }
}
