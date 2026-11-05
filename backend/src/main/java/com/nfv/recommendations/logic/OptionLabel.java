package com.nfv.recommendations.logic;

/** Feature of a {@link RouteOption}, the frontend builds the texts. */
public enum OptionLabel {
    /** Earliest arrival of the set. */
    FASTEST,
    /** Shortest distance of the set. */
    SHORTEST,
    /** Same way as the planned route. */
    CURRENT,
    /** Avoids an incident the planned route passes. */
    AVOIDS_INCIDENT,
    /** Passes an incident. */
    HAS_INCIDENT,
    LATER_DEPARTURE,
    EARLIER_DEPARTURE,
    /** Uses a ferry (not set yet, needs the road environment of the segments). */
    FERRY
}