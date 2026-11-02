package com.nfv.recommendations.entities;

/** What a {@link SuggestionSet} was built for, see Docs/recommendations.md#szenario-recommendationslogic. */
public enum SuggestionTrigger {
    /** The user opened the route. */
    PLANNING,
    /** An incident affects the planned route. */
    INCIDENT
}