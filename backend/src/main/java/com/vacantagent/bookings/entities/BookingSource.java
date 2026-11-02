package com.vacantagent.bookings.entities;

/** How the booking got into the app. */
public enum BookingSource {
    /** Typed in by the user. */
    MANUAL,
    /** Found by name (voice or text search), follows. */
    SEARCH,
    /** Read from a pasted confirmation, a screenshot or a PDF. */
    DOCUMENT,
    /** Shared to the installed app (share target). */
    SHARE,
    /** Forwarded by e-mail, follows. */
    EMAIL
}
