package com.nfv.bookings.logic;

import com.nfv.bookings.entities.BookingReservation;
import jakarta.annotation.Nullable;

/**
 * @param approveUrl where the user approves the payment at PayPal, only while it is pending
 * @param booking    the created booking once booked
 */
public record ReservationView(BookingReservation reservation, long tripId, @Nullable String approveUrl,
                              @Nullable BookingView booking) {
}
