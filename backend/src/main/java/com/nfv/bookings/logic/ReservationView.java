package com.nfv.bookings.logic;

import com.nfv.bookings.entities.BookingReservation;
import jakarta.annotation.Nullable;

import java.util.List;

/**
 * @param approveUrl        where the user approves the payment at PayPal, only while it is pending
 * @param booking           the created booking once booked
 * @param replannedRouteIds routes planned again because the stop moved to the place of the new hotel, only in the
 *                          answer that booked it
 */
public record ReservationView(BookingReservation reservation, long tripId, @Nullable String approveUrl,
                              @Nullable BookingView booking, List<Long> replannedRouteIds) {

    public ReservationView(BookingReservation reservation, long tripId, @Nullable String approveUrl,
                           @Nullable BookingView booking) {
        this(reservation, tripId, approveUrl, booking, List.of());
    }
}
