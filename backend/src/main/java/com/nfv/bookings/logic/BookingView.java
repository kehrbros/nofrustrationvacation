package com.nfv.bookings.logic;

import com.nfv.bookings.entities.AccommodationDetails;
import com.nfv.bookings.entities.Booking;
import jakarta.annotation.Nullable;

/** A booking with its accommodation details, if it is one. */
public record BookingView(Booking booking, @Nullable AccommodationDetails accommodation) {
}
