package com.nfv.incidents.logic;

import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.entities.IncidentType;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

/**
 * Whether an incident affects a passage, shared by {@code ConsequenceMatcher} and the evaluation of route suggestions,
 * see Docs/recommendations.md#bewertung-aussortieren-ranking.
 */
public final class IncidentOverlap {
    private IncidentOverlap() {
    }

    /** Whether a passage during {@code [start, end]} falls into the validity {@code [validFrom, validTo]}. */
    public static boolean overlaps(OffsetDateTime validFrom, @Nullable OffsetDateTime validTo, OffsetDateTime start,
                                   OffsetDateTime end) {
        return !end.isBefore(validFrom) && (validTo == null || !start.isAfter(validTo));
    }

    public static Impact impactOf(IncidentType type) {
        return type == IncidentType.CLOSURE ? Impact.BLOCKED : Impact.DELAYED;
    }
}