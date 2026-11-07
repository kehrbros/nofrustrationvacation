package com.nfv.recommendations.logic;

import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.NoRouteException;
import com.nfv.recommendations.config.RecommendationProperties;
import com.nfv.trips.logic.RouteSnapshot;
import com.nfv.trips.logic.RouteSnapshots;
import com.nfv.trips.logic.TripItemNotFoundException;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Route suggestions for other parts of the app (REST, later notifications and MCP), see
 * Docs/recommendations.md#schnittstellen. Computed per request and not stored; routing runs outside of
 * transactions, as in {@code RoutePlanning}.
 */
@Service
@RequiredArgsConstructor
public class Suggestions {
    private final RouteSnapshots routeSnapshots;
    private final SuggestionEngine engine;
    private final RecommendationProperties properties;

    /**
     * Suggestions when planning. Missing coordinates or departure and {@code NO_ROUTE} are a {@code problem}.
     *
     * @param count            suggestions wanted, {@code null}: default
     * @param includeWaypoints {@code false}: only between the stops, waypoints are ignored
     * @param departure        departure to try instead of the one at the start stop, not stored at the stop
     * @throws TripItemNotFoundException                         route unknown or of another user
     * @throws InvalidSuggestionInputException                   count out of range
     * @throws com.nfv.network.logic.RoutingUnavailableException no graph yet
     */
    public SuggestionResult forPlanning(long appUserId, long routeId, @Nullable Integer count,
                                        boolean includeWaypoints, @Nullable OffsetDateTime departure) {
        int wanted = count == null ? properties.defaultCount() : count;
        if (wanted < 1 || wanted > properties.maxCount()) {
            throw new InvalidSuggestionInputException("count must be between 1 and " + properties.maxCount());
        }
        RouteSnapshot route = routeSnapshots.of(appUserId, routeId);
        ScenarioFactory.Built built = ScenarioFactory.forPlanning(route, wanted, includeWaypoints, departure);
        if (built.problem() != null) {
            return SuggestionResult.problem(built.problem());
        }
        Scenario scenario = Objects.requireNonNull(built.scenario());
        SuggestionEngine.Result result;
        try {
            result = engine.run(scenario);
        } catch (NoRouteException e) {
            return SuggestionResult.problem(SuggestionProblem.NO_ROUTE);
        }
        return new SuggestionResult(scenario.waypoints(), scenario.departure(), result.options(),
                reference(result.reference()), null);
    }

    /**
     * The route to a stop as if the stop moved to {@code end} (new accommodation in another place), best way first,
     * nothing is stored. Without departure or coordinates of the start stop a {@code problem}.
     *
     * @throws TripItemNotFoundException                         route unknown or of another user
     * @throws com.nfv.network.logic.RoutingUnavailableException no graph yet
     */
    public SuggestionResult towards(long appUserId, long routeId, GeoPoint end) {
        RouteSnapshot route = routeSnapshots.of(appUserId, routeId);
        ScenarioFactory.Built built = ScenarioFactory.towards(route, end, 1);
        if (built.problem() != null) {
            return SuggestionResult.problem(built.problem());
        }
        Scenario scenario = Objects.requireNonNull(built.scenario());
        try {
            SuggestionEngine.Result result = engine.run(scenario);
            return new SuggestionResult(scenario.waypoints(), scenario.departure(), result.options(), null, null);
        } catch (NoRouteException e) {
            return SuggestionResult.problem(SuggestionProblem.NO_ROUTE);
        }
    }

    @Nullable
    private static SuggestionResult.Reference reference(@Nullable CandidateEvaluator.Outcome outcome) {
        return outcome == null ? null : new SuggestionResult.Reference(outcome.departure(), outcome.arrival(),
                outcome.distanceMeters(), outcome.duration(), outcome.delay(), outcome.blocked(),
                outcome.conflicts());
    }
}
