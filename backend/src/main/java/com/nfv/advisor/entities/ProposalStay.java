package com.nfv.advisor.entities;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The offer of a {@code STOPOVER} or {@code TARGET_STAY} proposal, as the search found it. Holds what booking it
 * needs, the backend checks the offer again then (prebook), see Docs/advisor.md#search_accommodations. The price is in
 * the currency of the offer, like {@code BookingReservation}, see Docs/money.md.
 *
 * @param place             the area or the target stop the search looked around
 * @param distanceMeters    from {@code place}, straight line
 * @param price             total for the stay including our fee
 * @param freeCancellationUntil local at the hotel
 * @param replacesBookingId {@code TARGET_STAY} only: the accommodation booked at the target stop, if there is one
 */
@Embeddable
public record ProposalStay(
        @Column(name = "stay_hotel_id", length = 64) String hotelId,
        @Column(name = "stay_offer_id", length = 1000) String offerId,
        @Column(name = "stay_hotel_name") String hotelName,
        @Column(name = "stay_latitude") double latitude,
        @Column(name = "stay_longitude") double longitude,
        @Column(name = "stay_place") String place,
        @Column(name = "stay_distance_meters") int distanceMeters,
        @Column(name = "stay_check_in") LocalDate checkIn,
        @Column(name = "stay_check_out") LocalDate checkOut,
        @Column(name = "stay_guests") int guests,
        @Column(name = "stay_rooms") int rooms,
        @Column(name = "stay_price", precision = 12, scale = 2) BigDecimal price,
        @Column(name = "stay_currency", length = 3) String currency,
        @Column(name = "stay_refundable") boolean refundable,
        @Column(name = "stay_free_cancellation_until") @Nullable LocalDateTime freeCancellationUntil,
        @Column(name = "stay_replaces_booking_id") @Nullable Long replacesBookingId) {
}
