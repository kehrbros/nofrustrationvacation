package com.nfv.trips.dto;

/** How a change of a stop resolves bookings that would lie outside its new days. */
public enum BookingConflict {
    /** Shorten the bookings to the new days (only in the app, the provider is not contacted). */
    TRIM_BOOKINGS
}
