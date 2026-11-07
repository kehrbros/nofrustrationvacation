package com.nfv.bookings.entities;

/** What the user has to do about a booking, see {@link BookingTodo}. */
public enum TodoKind {
    /** The booking is not needed any more: cancel it at the provider (or in the app if booked there). */
    CANCEL,
    /** The booking stays, but the arrival moves to a later day: tell the accommodation, maybe cancel the first night. */
    NOTIFY_LATE_ARRIVAL
}
