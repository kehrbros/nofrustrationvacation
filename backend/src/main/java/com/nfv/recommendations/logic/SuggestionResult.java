package com.nfv.recommendations.logic;

import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Suggestions for a route, computed per request and not stored, or why there are none.
 *
 * @param waypointMode {@code null} if there is a {@code problem}
 * @param departureAt  departure the options were computed from, {@code null} if there is a {@code problem}
 * @param options      best first, empty if there is a {@code problem}
 * @param reference    planned route evaluated like the options, {@code null} if not planned
 */
public record SuggestionResult(@Nullable WaypointMode waypointMode, @Nullable OffsetDateTime departureAt,
                               List<SuggestedOption> options, @Nullable Reference reference,
                               @Nullable SuggestionProblem problem) {

    /** Planned route with arrival including delays, e.g. "as planned (+45 min)" or "as planned (closed)". */
    public record Reference(OffsetDateTime departureAt, OffsetDateTime arrivalAt, double distanceMeters,
                            Duration duration, Duration delay, boolean blocked,
                            List<SuggestedOption.Conflict> conflicts) {
    }

    static SuggestionResult problem(SuggestionProblem problem) {
        return new SuggestionResult(null, null, List.of(), null, problem);
    }
}
