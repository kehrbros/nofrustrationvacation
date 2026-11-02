package com.nfv.trips.logic;

import com.nfv.trips.entities.WaypointOrigin;
import jakarta.annotation.Nullable;

/**
 * Waypoint in the ordered list of a route: with {@code id} an existing one is changed, without a new one created.
 *
 * @param origin only used when created, an existing waypoint keeps its origin
 */
public record NewWaypoint(@Nullable Long id, double latitude, double longitude, @Nullable String name,
                          @Nullable String roadHint, WaypointOrigin origin) {
    /** Waypoint chosen by the user. */
    public NewWaypoint(@Nullable Long id, double latitude, double longitude, @Nullable String name,
                       @Nullable String roadHint) {
        this(id, latitude, longitude, name, roadHint, WaypointOrigin.USER);
    }
}