package com.vacantagent.trips.logic;

import jakarta.annotation.Nullable;

/** Outcome of {@link RoutePlanning#plan}; {@code reason} explains everything but {@code PLANNED}. */
public record PlanResult(Outcome outcome, @Nullable String reason) {
    public enum Outcome {
        PLANNED,
        /** A stop has no coordinates yet, the route stays unplanned. */
        MISSING_COORDINATES,
        /** The start stop has no departure, without it there are no passage times. */
        MISSING_DEPARTURE,
        /** No route between the points, e.g. a point far from any road. */
        NO_ROUTE
    }
}