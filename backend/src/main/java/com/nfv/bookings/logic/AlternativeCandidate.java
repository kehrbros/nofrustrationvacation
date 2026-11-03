package com.nfv.bookings.logic;

import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Offer of a hotel that meets all criteria, with our price (net rate plus fee).
 *
 * @param possiblySame within 50 m of the current accommodation, may be the same place under another name
 * @param reason       why the assistant ranks it there, null without assistant
 */
public record AlternativeCandidate(String hotelId, String name, @Nullable String address, double latitude,
                                   double longitude, int distanceMeters, @Nullable Double stars,
                                   @Nullable Double rating, @Nullable String photoUrl, String offerId,
                                   @Nullable String roomName, @Nullable String boardName, boolean refundable,
                                   @Nullable LocalDateTime freeCancellationUntil, BigDecimal price, String currency,
                                   boolean possiblySame, @Nullable String reason) {

    AlternativeCandidate withReason(@Nullable String reason) {
        return new AlternativeCandidate(hotelId, name, address, latitude, longitude, distanceMeters, stars, rating,
                photoUrl, offerId, roomName, boardName, refundable, freeCancellationUntil, price, currency,
                possiblySame, reason);
    }
}
