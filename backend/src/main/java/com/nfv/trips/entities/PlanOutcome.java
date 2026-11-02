package com.nfv.trips.entities;

/** Outcome of planning a {@link Route}, see Docs/domain.md#trips. */
public enum PlanOutcome {
    PLANNED,
    /** A stop has no coordinates yet. */
    MISSING_COORDINATES,
    /** The start stop has no departure, without it there are no passage times. */
    MISSING_DEPARTURE,
    /** No route between the points, e.g. a point far from any road. */
    NO_ROUTE,
    /** Road network not loaded yet, plan again later. */
    ROUTING_UNAVAILABLE
}