package com.nfv.bookings.logic;

import jakarta.annotation.Nullable;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Whether a booking lies within the days of its stop, see Docs/domain.md#bookings. Only the days count
 * (the time of a booking is internal): check-in not before the arrival day, check-out not after the departure day.
 * Days are local, the booking at its place, the stop with its own offset. Without arrival or departure that side is
 * open.
 */
final class StayFit {
    private StayFit() {
    }

    /** Check-in and check-out of a booking, local at the place. */
    record Stay(@Nullable OffsetDateTime startsAt, @Nullable OffsetDateTime endsAt) {
    }

    static boolean outside(Stay stay, @Nullable OffsetDateTime arrivalAt, @Nullable OffsetDateTime departureAt) {
        return startsTooEarly(stay, arrivalAt) || endsTooLate(stay, departureAt);
    }

    static boolean startsTooEarly(Stay stay, @Nullable OffsetDateTime arrivalAt) {
        return stay.startsAt() != null && arrivalAt != null
                && stay.startsAt().toLocalDate().isBefore(arrivalAt.toLocalDate());
    }

    static boolean endsTooLate(Stay stay, @Nullable OffsetDateTime departureAt) {
        return stay.endsAt() != null && departureAt != null
                && stay.endsAt().toLocalDate().isAfter(departureAt.toLocalDate());
    }

    /**
     * The stay shortened to the days of the stop, same times of day.
     *
     * @param zone zone of the place, {@code null}: the offset of the booking stays
     * @return empty if no night would be left
     */
    static Optional<Stay> trim(Stay stay, @Nullable ZoneId zone, @Nullable OffsetDateTime arrivalAt,
                               @Nullable OffsetDateTime departureAt) {
        OffsetDateTime startsAt = startsTooEarly(stay, arrivalAt)
                ? onDay(stay.startsAt(), arrivalAt.toLocalDate(), zone) : stay.startsAt();
        OffsetDateTime endsAt = endsTooLate(stay, departureAt)
                ? onDay(stay.endsAt(), departureAt.toLocalDate(), zone) : stay.endsAt();
        if (startsAt != null && endsAt != null && !endsAt.toLocalDate().isAfter(startsAt.toLocalDate())) {
            return Optional.empty();
        }
        return Optional.of(new Stay(startsAt, endsAt));
    }

    /** Same local time on another day, with the offset valid there (summer time). */
    static OffsetDateTime onDay(OffsetDateTime time, LocalDate day, @Nullable ZoneId zone) {
        return zone == null ? time.with(day) : time.toLocalDateTime().with(day).atZone(zone).toOffsetDateTime();
    }
}
