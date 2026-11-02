package com.vacantagent.bookings.logic;

import com.vacantagent.bookings.entities.AccommodationDetails;
import com.vacantagent.bookings.entities.Booking;
import jakarta.annotation.Nullable;

/** A booking with its accommodation details, if it is one. */
public record BookingView(Booking booking, @Nullable AccommodationDetails accommodation) {
}
