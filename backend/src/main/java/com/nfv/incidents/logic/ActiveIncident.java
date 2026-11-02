package com.nfv.incidents.logic;

import com.nfv.incidents.entities.Impact;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;

/** Incident for other modules, without the entity, see {@link ActiveIncidents}. */
public record ActiveIncident(long id, Impact impact, @Nullable Duration expectedDelay, OffsetDateTime validFrom,
                             @Nullable OffsetDateTime validTo, Set<Long> segmentIds) {
    /** Whether a passage during {@code [start, end]} falls into the validity. */
    public boolean overlaps(OffsetDateTime start, OffsetDateTime end) {
        return IncidentOverlap.overlaps(validFrom, validTo, start, end);
    }
}