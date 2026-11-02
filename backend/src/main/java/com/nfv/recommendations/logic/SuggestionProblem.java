package com.nfv.recommendations.logic;

/** Why there are no suggestions, same meaning as the plan outcomes of a route. */
public enum SuggestionProblem {
    /** A stop has no coordinates yet. */
    MISSING_COORDINATES,
    /** The start stop has no departure and none was given. */
    MISSING_DEPARTURE,
    /** No way between the points, e.g. a point far from any road. */
    NO_ROUTE
}