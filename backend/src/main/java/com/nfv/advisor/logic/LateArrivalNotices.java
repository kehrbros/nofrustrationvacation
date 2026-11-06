package com.nfv.advisor.logic;

import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Draft of a message to the accommodation about a late arrival, sending it stays with the user, see
 * Docs/advisor.md#später-unterkünfte-und-nachricht-an-die-unterkunft. Not implemented yet, so no tool uses it.
 */
public interface LateArrivalNotices {
    /** Empty if the stop has no accommodation to write to. */
    Optional<LateArrivalNotice> draft(long appUserId, long stopId, OffsetDateTime expectedArrival);

    /** The backend adds the contact details, they never go to the model. */
    record LateArrivalNotice(long bookingId, String accommodationName, @Nullable String email, @Nullable String phone,
                             String message) {
    }
}
