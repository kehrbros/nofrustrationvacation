package com.nfv.incidents.logic;

import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.entities.IncidentType;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;

/**
 * Whether an incident affects a passage, shared by {@code ConsequenceMatcher} and the evaluation of route suggestions,
 * see Docs/recommendations.md#assessment-filtering-ranking. The validity {@code [validFrom, validTo]} is the
 * envelope; an incident with time windows applies only within them, see Docs/traffic-feed.md#time-windows.
 */
public final class IncidentOverlap {
    private IncidentOverlap() {
    }

    /**
     * Whether a passage during {@code [start, end]} falls into the validity {@code [validFrom, validTo]}. An incident
     * that ended before it started (cancelled) has a validity without length and affects nothing.
     */
    public static boolean overlaps(OffsetDateTime validFrom, @Nullable OffsetDateTime validTo, OffsetDateTime start,
                                   OffsetDateTime end) {
        if (validTo != null && !validTo.isAfter(validFrom)) {
            return false;
        }
        return !end.isBefore(validFrom) && (validTo == null || !start.isAfter(validTo));
    }

    /**
     * Whether a passage falls into the validity and, if there are windows, into at least one of them.
     *
     * @param windows empty: the whole validity
     */
    public static boolean overlaps(OffsetDateTime validFrom, @Nullable OffsetDateTime validTo,
                                   Collection<TimeWindow> windows, OffsetDateTime start, OffsetDateTime end) {
        return overlaps(validFrom, validTo, start, end)
                && (windows.isEmpty() || firstWindow(windows, start, end).isPresent());
    }

    /** Earliest window the passage falls into, empty if none (or no windows). */
    public static Optional<TimeWindow> firstWindow(Collection<TimeWindow> windows, OffsetDateTime start,
                                                   OffsetDateTime end) {
        return windows.stream()
                .filter(window -> window.overlaps(start, end))
                .min(Comparator.comparing(TimeWindow::startsAt));
    }

    public static Impact impactOf(IncidentType type) {
        return type == IncidentType.CLOSURE ? Impact.BLOCKED : Impact.DELAYED;
    }
}
