package com.nfv.trips.logic;

import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

/**
 * Published within the transaction after arrival or departure of a stop changed by the user. The bookings module
 * checks the bookings at the stop against the new days; to refuse the change, its listener throws
 * {@link StopBookingsConflictException}, which rolls the transaction back.
 *
 * @param fit {@link BookingFit#CHECK} or {@link BookingFit#TRIM_BOOKINGS}, never published for {@code IGNORE}
 */
public record StopRescheduling(long stopId, @Nullable OffsetDateTime arrivalAt, @Nullable OffsetDateTime departureAt,
                               BookingFit fit) {
}
