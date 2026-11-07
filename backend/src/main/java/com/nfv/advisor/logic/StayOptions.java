package com.nfv.advisor.logic;

import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Accommodations when a route cannot be driven in time, see Docs/advisor.md#search_accommodations. Places on the route
 * belong to {@code recommendations} ({@code Stopovers}). The adapter on {@code bookings} is {@link LiteApiStayOptions},
 * so that {@code bookings} does not know this module. Never calls the model.
 */
public interface StayOptions {
    /** LiteAPI is configured, else the tool is not offered. */
    boolean available();

    /**
     * Offers that meet all criteria, cheapest first. Every call counts against the searches of the user.
     *
     * @throws InvalidStaySearchException    criteria out of range, the model can correct them
     * @throws StaySearchUnavailableException searches used up or LiteAPI failing
     */
    List<StayOffer> search(long appUserId, StaySearch search);

    /** The accommodation booked at the stop, with what a search for its replacement starts from. */
    Optional<BookedStay> bookedStay(long appUserId, long tripId, long stopId);

    /**
     * @param maxPrice       total for the stay including our fee, in {@code currency}; null: no limit
     * @param excludeHotelId LiteAPI id of the booked accommodation, it is no alternative to itself
     */
    record StaySearch(double latitude, double longitude, int radiusMeters, LocalDate checkIn, LocalDate checkOut,
                      int guests, int rooms, @Nullable BigDecimal maxPrice, String currency,
                      @Nullable String excludeHotelId, int maxResults) {
    }

    /**
     * With the position of the hotel, for the new stop when the user takes the stay.
     *
     * @param price                 total for the stay including our fee
     * @param freeCancellationUntil local at the hotel
     */
    record StayOffer(String hotelId, String offerId, String name, double latitude, double longitude,
                     int distanceMeters, @Nullable Double stars, @Nullable Double rating, @Nullable String boardName,
                     BigDecimal price, String currency, boolean refundable,
                     @Nullable LocalDateTime freeCancellationUntil) {
    }

    /** The values are what the booking provides, null if it does not. */
    record BookedStay(long bookingId, @Nullable Double latitude, @Nullable Double longitude,
                      @Nullable LocalDate checkIn, @Nullable LocalDate checkOut, @Nullable Integer guests, int rooms,
                      @Nullable BigDecimal maxPrice, String currency, @Nullable String hotelId) {
    }

    class InvalidStaySearchException extends RuntimeException {
        public InvalidStaySearchException(String message) {
            super(message);
        }
    }

    class StaySearchUnavailableException extends RuntimeException {
        private final boolean rateLimited;

        public StaySearchUnavailableException(String message, boolean rateLimited) {
            super(message);
            this.rateLimited = rateLimited;
        }

        /** The searches of the user are used up for now. Otherwise LiteAPI failed. */
        public boolean rateLimited() {
            return rateLimited;
        }
    }
}
