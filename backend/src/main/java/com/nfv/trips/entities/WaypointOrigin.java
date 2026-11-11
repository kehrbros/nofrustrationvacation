package com.nfv.trips.entities;

/** Who set a {@link Waypoint}, see Docs/recommendations.md#data-model. */
public enum WaypointOrigin {
    /** Chosen by the user. */
    USER,
    /** Created by applying a route suggestion, reproduces its way. */
    SUGGESTION
}