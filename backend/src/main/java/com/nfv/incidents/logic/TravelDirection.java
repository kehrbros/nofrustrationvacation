package com.nfv.incidents.logic;

import jakarta.annotation.Nullable;

/** Direction of travel an incident applies to; compass directions keep segments heading that way (± 60°). */
public enum TravelDirection {
    BOTH(null),
    NORTHBOUND(0.0),
    EASTBOUND(90.0),
    SOUTHBOUND(180.0),
    WESTBOUND(270.0);

    private final @Nullable Double bearing;

    TravelDirection(@Nullable Double bearing) {
        this.bearing = bearing;
    }

    /** Bearing in degrees (0 = north), {@code null} for both directions. */
    public @Nullable Double bearing() {
        return bearing;
    }
}
