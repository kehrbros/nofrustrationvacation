package com.nfv.bookings.entities;

/** Why a {@link BookingTodo} came up. */
public enum TodoReason {
    /** Another accommodation was booked in its place. */
    REPLACED,
    /** Its stop was deleted, e.g. while planning again by hand. */
    STOP_REMOVED,
    /** A night on the way makes the arrival at its stop a day later. */
    ARRIVAL_LATER
}
