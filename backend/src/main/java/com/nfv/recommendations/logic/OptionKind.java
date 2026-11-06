package com.nfv.recommendations.logic;

/** Generator a {@link RouteOption} comes from, see Docs/recommendations.md#generatoren. */
public enum OptionKind {
    /** GraphHopper {@code alternative_route}, the fastest included. */
    ALTERNATIVES,
    /** The planned route with another departure (incidents). */
    DEPARTURE_SHIFT
}