package com.nfv.incidents.logic;

import java.time.OffsetDateTime;

/** Period within the validity of an incident in which it applies, e.g. a closure from 21:00 to 04:30. */
public record TimeWindow(OffsetDateTime startsAt, OffsetDateTime endsAt) {
    public TimeWindow {
        if (!endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("time window ends before it starts");
        }
    }

    /** Whether a passage during {@code [start, end]} touches the window. */
    public boolean overlaps(OffsetDateTime start, OffsetDateTime end) {
        return !end.isBefore(startsAt) && !start.isAfter(endsAt);
    }
}
