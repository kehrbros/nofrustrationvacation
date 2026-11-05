package com.nfv.recommendations.logic;

/** Generator a {@link RouteOption} comes from, see Docs/recommendations.md#generatoren. */
public enum OptionKind {
    /** GraphHopper {@code alternative_route}, the fastest included. */
    ALTERNATIVES,
    /** The planned route as it is (incidents, later). */
    KEEP_ROUTE,
    /** The planned route with another departure (incidents, later). */
    DEPARTURE_SHIFT
}