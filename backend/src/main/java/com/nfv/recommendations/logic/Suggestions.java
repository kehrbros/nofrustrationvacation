package com.nfv.recommendations.logic;

import com.nfv.incidents.logic.ActiveIncidents;
import com.nfv.incidents.logic.RouteConsequence;
import com.nfv.network.logic.NoRouteException;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.network.logic.SegmentQueries;
import com.nfv.recommendations.config.RecommendationProperties;
import com.nfv.trips.logic.PlannedSection;
import com.nfv.trips.logic.RouteSnapshot;
import com.nfv.trips.logic.RouteSnapshots;
import com.nfv.trips.logic.TripItemNotFoundException;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final ActiveIncidents activeIncidents;
    private final SegmentQueries segmentQueries;
    private final Clock clock;

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
        int wanted = wanted(count);
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
     * Suggestions for an incident on the route: the waypoints stay, only legs that pass an incident get alternatives.
     * A problem still carries the planned route as comparison if it could be evaluated.
     *
     * @param kinds     what to look for; call once per kind to get {@code count} of each
     * @param count     suggestions wanted, {@code null}: default
     * @param departure departure to try instead of the planned one, ignored once the route has started
     * @throws TripItemNotFoundException                         consequence unknown or on a route of another user
     * @throws InvalidSuggestionInputException                   no kind, count out of range or departure in the past
     * @throws com.nfv.network.logic.RoutingUnavailableException no graph yet
     */
    public SuggestionResult forConsequence(long appUserId, long consequenceId, Set<OptionKind> kinds,
                                           @Nullable Integer count, @Nullable OffsetDateTime departure) {
        int wanted = wanted(count);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (kinds.isEmpty()) {
            throw new InvalidSuggestionInputException("at least one kind of suggestion");
        }
        if (departure != null && departure.isBefore(now)) {
            throw new InvalidSuggestionInputException("departure must not be in the past");
        }
        RouteConsequence consequence = activeIncidents.consequence(consequenceId)
                .orElseThrow(() -> new TripItemNotFoundException("consequence", consequenceId));
        RouteSnapshot route = routeSnapshots.of(appUserId, consequence.routeId());
        Map<Long, SegmentInfo> segments = segmentQueries.segments(
                route.sections().stream().map(PlannedSection::segmentId).collect(Collectors.toSet()));
        List<RouteConsequence> all = activeIncidents.consequencesOnRoute(route.routeId());
        ScenarioFactory.Built built = ScenarioFactory.forConsequence(route, consequence, all, kinds, wanted, departure,
                now, segments);
        if (built.problem() != null) {
            return SuggestionResult.problem(built.problem());
        }
        Scenario scenario = Objects.requireNonNull(built.scenario());
        SuggestionEngine.Result result = engine.run(scenario);
        SuggestionProblem problem = result.options().isEmpty() && scenario.generators().contains(OptionKind.ALTERNATIVES)
                ? scenario.vias().isEmpty() ? SuggestionProblem.NO_ROUTE : SuggestionProblem.WAYPOINT_UNREACHABLE
                : null;
        return new SuggestionResult(scenario.waypoints(), scenario.departure(), result.options(),
                reference(result.reference()), problem);
    }

    private int wanted(@Nullable Integer count) {
        int wanted = count == null ? properties.defaultCount() : count;
        if (wanted < 1 || wanted > properties.maxCount()) {
            throw new InvalidSuggestionInputException("count must be between 1 and " + properties.maxCount());
        }
        return wanted;
    }

    @Nullable
    private static SuggestionResult.Reference reference(@Nullable CandidateEvaluator.Outcome outcome) {
        return outcome == null ? null : new SuggestionResult.Reference(outcome.departure(), outcome.arrival(),
                outcome.distanceMeters(), outcome.duration(), outcome.delay(), outcome.blocked(),
                outcome.conflicts());
    }
}
