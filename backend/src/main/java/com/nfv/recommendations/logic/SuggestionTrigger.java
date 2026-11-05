package com.nfv.recommendations.logic;

/** What suggestions are computed for, see Docs/recommendations.md#szenario-recommendationslogic. */
public enum SuggestionTrigger {
    /** The user opened the route. */
    PLANNING,
    /** An incident affects the planned route. */
    INCIDENT
}