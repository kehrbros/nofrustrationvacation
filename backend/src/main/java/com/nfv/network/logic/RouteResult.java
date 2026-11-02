package com.nfv.network.logic;

import java.time.Duration;
import java.util.List;

/** Result of {@link Routing#route}, one entry in {@code snappedPoints} per requested point. */
public record RouteResult(String region, long extractVersionId, double distanceMeters, Duration duration,
                          GeoLine geometry, List<RoutedSegment> segments, List<SnappedPoint> snappedPoints) {
}