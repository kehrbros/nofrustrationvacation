package com.vacantagent.trips.logic;

import com.vacantagent.trips.entities.RouteSection;

import java.time.OffsetDateTime;

/** One section of a planned route, in driving order; fractions as in {@link RouteSection}. */
public record PlannedSection(long segmentId, double fromFraction, double toFraction,
                             OffsetDateTime expectedPassageStart, OffsetDateTime expectedPassageEnd) {
    /** Section over the whole segment. */
    public PlannedSection(long segmentId, OffsetDateTime expectedPassageStart, OffsetDateTime expectedPassageEnd) {
        this(segmentId, 0, 1, expectedPassageStart, expectedPassageEnd);
    }
}