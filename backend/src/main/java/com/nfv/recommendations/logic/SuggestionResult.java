package com.nfv.recommendations.logic;

import com.nfv.recommendations.entities.RouteOption;
import com.nfv.recommendations.entities.SuggestionSet;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Suggestions for a route: the stored set with its options (loaded), the planned route for comparison, or why there
 * are none.
 *
 * @param set       {@code null} if there is a {@code problem}
 * @param reference planned route evaluated like the options, {@code null} if not planned
 */
public record SuggestionResult(@Nullable SuggestionSet set, @Nullable Reference reference,
                               @Nullable SuggestionProblem problem) {

    /** Planned route with arrival including delays, e.g. "as planned (+45 min)" or "as planned (closed)". */
    public record Reference(OffsetDateTime departureAt, OffsetDateTime arrivalAt, double distanceMeters,
                            Duration duration, Duration delay, boolean blocked, List<RouteOption.Conflict> conflicts) {
    }

    static SuggestionResult problem(SuggestionProblem problem) {
        return new SuggestionResult(null, null, problem);
    }
}