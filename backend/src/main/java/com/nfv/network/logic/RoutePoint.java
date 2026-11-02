package com.nfv.network.logic;

import jakarta.annotation.Nullable;

/**
 * Point of a route request: stops are only first and last, everything in between is a via point.
 *
 * @param roadHint name or number of the road the point should snap to, e.g. {@code SH 73}
 */
public record RoutePoint(GeoPoint point, Kind kind, @Nullable String roadHint) {
    public enum Kind {
        STOP, VIA
    }
}