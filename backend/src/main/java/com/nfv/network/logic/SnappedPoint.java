package com.nfv.network.logic;

/**
 * Where a requested point was snapped to.
 *
 * @param distanceMeters  between requested and snapped point
 * @param segmentPosition index into {@link RouteResult#segments()} of the segment the point lies on
 */
public record SnappedPoint(GeoPoint point, double distanceMeters, int segmentPosition) {
}