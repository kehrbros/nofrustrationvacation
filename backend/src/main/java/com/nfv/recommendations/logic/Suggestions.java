package com.nfv.recommendations.logic;

import com.nfv.network.logic.NoRouteException;
import com.nfv.recommendations.config.RecommendationProperties;
import com.nfv.recommendations.data.SuggestionSetRepository;
import com.nfv.recommendations.entities.RouteOption;
import com.nfv.recommendations.entities.SuggestionSet;
import com.nfv.recommendations.entities.SuggestionStatus;
import com.nfv.recommendations.entities.WaypointMode;
import com.nfv.trips.logic.RouteSnapshot;
import com.nfv.trips.logic.TripItemNotFoundException;
import com.nfv.trips.logic.RouteSnapshots;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Route suggestions for other parts of the app (REST, later notifications and MCP), see
 * Docs/recommendations.md#schnittstellen. Routing runs outside of transactions, as in {@code RoutePlanning}.
 */
@Service
@RequiredArgsConstructor
public class Suggestions {
    private final RouteSnapshots routeSnapshots;
    private final SuggestionEngine engine;
    private final SuggestionSetRepository setRepository;
    private final RecommendationProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    /**
     * Suggestions when planning: a stored {@code CURRENT} set with the same basis is reused, otherwise computed and
     * stored. Missing coordinates or departure and {@code NO_ROUTE} are a {@code problem}, no set.
     *
     * @param count            suggestions wanted, {@code null}: default
     * @param includeWaypoints {@code false}: only between the stops, waypoints are ignored
     * @param departure        departure to try instead of the one at the start stop, not stored at the stop
     * @throws TripItemNotFoundException                          route unknown or of another user
     * @throws InvalidSuggestionInputException                    count out of range
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
        String hash = BasisHash.of(scenario);
        OffsetDateTime now = OffsetDateTime.now(clock);
        SuggestionSet existing = transactions.execute(status -> setRepository
                .findFirstByRouteIdAndBasisHashAndStatusOrderByCreatedAtDesc(routeId, hash, SuggestionStatus.CURRENT)
                .filter(set -> set.isCurrent(now))
                .map(Suggestions::loaded)
                .orElse(null));
        if (existing != null) {
            return new SuggestionResult(existing, reference(engine.reference(scenario)), null);
        }
        SuggestionEngine.Result result;
        try {
            result = engine.run(scenario);
        } catch (NoRouteException e) {
            return SuggestionResult.problem(SuggestionProblem.NO_ROUTE);
        }
        SuggestionSet saved = transactions.execute(status -> {
            SuggestionSet set = new SuggestionSet(routeId, scenario.trigger(), scenario.waypoints(), wanted,
                    scenario.departure(), hash, now, now.plus(properties.planningLifetime()));
            result.options().forEach(set::add);
            return setRepository.save(set);
        });
        return new SuggestionResult(saved, reference(result.reference()), null);
    }

    /**
     * A stored set with its options; the planned route for comparison as of now.
     *
     * @throws SuggestionSetNotFoundException set unknown or of another user
     */
    public SuggestionResult get(long appUserId, long setId) {
        SuggestionSet set = transactions.execute(status -> setRepository.findById(setId)
                .map(Suggestions::loaded).orElse(null));
        if (set == null) {
            throw new SuggestionSetNotFoundException(setId);
        }
        RouteSnapshot route = ownedRoute(appUserId, set);
        ScenarioFactory.Built built = ScenarioFactory.forPlanning(route, set.getRequestedCount(),
                set.getWaypointMode() == WaypointMode.FOLLOW, set.getDepartureAt());
        return new SuggestionResult(set,
                built.scenario() == null ? null : reference(engine.reference(built.scenario())), null);
    }

    /** The route of the set if it belongs to the user, otherwise the set is not found either. */
    RouteSnapshot ownedRoute(long appUserId, SuggestionSet set) {
        try {
            return routeSnapshots.of(appUserId, set.getRouteId());
        } catch (TripItemNotFoundException e) {
            throw new SuggestionSetNotFoundException(set.getId());
        }
    }

    /** Loads options, labels, points and conflicts, so the set can be read outside of the transaction. */
    static SuggestionSet loaded(SuggestionSet set) {
        for (RouteOption option : set.getOptions()) {
            Hibernate.initialize(option.getLabels());
            Hibernate.initialize(option.getPoints());
            Hibernate.initialize(option.getConflicts());
        }
        return set;
    }

    @Nullable
    private static SuggestionResult.Reference reference(@Nullable CandidateEvaluator.Outcome outcome) {
        return outcome == null ? null : new SuggestionResult.Reference(outcome.departure(), outcome.arrival(),
                outcome.distanceMeters(), outcome.duration(), outcome.delay(), outcome.blocked(),
                outcome.conflicts());
    }
}