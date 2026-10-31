package com.vacantagent.network.logic;

import java.time.Duration;

/** One driven segment; fractions are below 0..1 only on the first and last segment. */
public record RoutedSegment(long segmentId, double fromFraction, double toFraction, double distanceMeters,
                            Duration duration) {
}