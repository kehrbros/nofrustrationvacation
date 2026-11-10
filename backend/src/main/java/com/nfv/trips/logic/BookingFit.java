package com.nfv.trips.logic;

/**
 * How a new stay of a stop treats the bookings at the stop, see Docs/domain.md#bookings. Bookings must lie
 * within the days of the stop (arrival day to departure day).
 */
public enum BookingFit {
    /** Refuse with {@link StopBookingsConflictException} if a booking would lie outside. Changes by the user. */
    CHECK,
    /** Shorten bookings that would lie outside to the new days; refused if one cannot be shortened. */
    TRIM_BOOKINGS,
    /** No check, for changes the app makes itself (chosen departure, stopover before the stop). */
    IGNORE
}
