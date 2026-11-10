package com.nfv.bookings.logic;

/** How a changed booking that lies outside the days of its stop is resolved, see Docs/domain.md#bookings. */
public enum StopConflict {
    /** Arrival and departure of the stop move to the days of the booking, same time of day. */
    EXTEND_STOP,
    /** The booking is shortened to the days of the stop. */
    TRIM_BOOKING
}
