package com.nfv.trips.logic;

import jakarta.annotation.Nullable;

/** Waypoint in the ordered list of a route: with {@code id} an existing one is changed, without a new one created. */
public record NewWaypoint(@Nullable Long id, double latitude, double longitude, @Nullable String name,
                          @Nullable String roadHint) {
}