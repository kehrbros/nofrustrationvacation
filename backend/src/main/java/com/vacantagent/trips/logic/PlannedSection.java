package com.vacantagent.trips.logic;

import java.time.OffsetDateTime;

/** One section of a planned route, in driving order. */
public record PlannedSection(long segmentId, OffsetDateTime expectedPassageStart, OffsetDateTime expectedPassageEnd) {
}
