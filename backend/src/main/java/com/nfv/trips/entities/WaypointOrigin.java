package com.nfv.trips.entities;

/** Who set a {@link Waypoint}, see Docs/recommendations.md#datenmodell-v29__recommendationssql. */
public enum WaypointOrigin {
    /** Chosen by the user. */
    USER,
    /** Created by applying a route suggestion, reproduces its way. */
    SUGGESTION
}