package com.nfv.incidents.logic;

import com.nfv.incidents.entities.Impact;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Incident for other modules, without the entity, see {@link ActiveIncidents}.
 *
 * @param windows empty: applies during the whole validity
 */
public record ActiveIncident(long id, Impact impact, @Nullable Duration expectedDelay, OffsetDateTime validFrom,
                             @Nullable OffsetDateTime validTo, Set<Long> segmentIds, List<TimeWindow> windows) {
    public ActiveIncident {
        windows = List.copyOf(windows);
    }

    /** Whether a passage during {@code [start, end]} falls into the validity and, if any, into a window. */
    public boolean overlaps(OffsetDateTime start, OffsetDateTime end) {
        return IncidentOverlap.overlaps(validFrom, validTo, windows, start, end);
    }
}
