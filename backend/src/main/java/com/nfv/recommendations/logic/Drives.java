package com.nfv.recommendations.logic;

import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.logic.ActiveIncident;
import com.nfv.incidents.logic.ActiveIncidents;
import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.NoRouteException;
import com.nfv.network.logic.RouteOptions;
import com.nfv.network.logic.RoutePoint;
import com.nfv.network.logic.RouteResult;
import com.nfv.network.logic.Routing;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A drive between two points at a given time, around the closures that hold then, for legs of a plan that are not a
 * route of the trip yet (the way on after a night, the way to another target), see Docs/incident-plans.md. Computed
 * per request, nothing is stored, no transaction.
 */
@Service
@RequiredArgsConstructor
public class Drives {
    /** Rounds of routing around closures found on the way. */
    static final int MAX_DETOURS = 3;
    /** Waiting for a closure to end is tried only if it ends within this time after the planned departure. */
    static final Duration MAX_WAIT = Duration.ofHours(10);

    private final SuggestionEngine engine;
    private final Routing routing;
    private final ActiveIncidents activeIncidents;

    /**
     * @param option   the best way, {@code null} if there is none that passes
     * @param waitedFor departure later than asked because a closure ends then, {@code null} if not waited
     * @param problem  {@code NO_ROUTE} (no way at all) or {@code BLOCKED} (every way passes a closure)
     */
    public record Drive(@Nullable SuggestedOption option, @Nullable OffsetDateTime waitedFor,
                        @Nullable DriveProblem problem) {
    }

    public enum DriveProblem {
        NO_ROUTE,
        BLOCKED
    }

    /**
     * The fastest way that does not pass a closure: first the direct one, then around the closures it meets, up to
     * {@link #MAX_DETOURS} times. Without such a way, and if all closures on the direct one end soon, the same drive
     * after the last of them ends.
     *
     * @throws com.nfv.network.logic.RoutingUnavailableException no graph yet
     */
    public Drive between(GeoPoint from, GeoPoint to, OffsetDateTime departure) {
        Drive drive = around(from, to, departure);
        if (drive.option() != null || drive.problem() == DriveProblem.NO_ROUTE) {
            return drive;
        }
        OffsetDateTime clear = clearAt(from, to, departure);
        if (clear == null || clear.isAfter(departure.plus(MAX_WAIT))) {
            return drive;
        }
        Drive later = around(from, to, clear);
        return later.option() == null ? drive : new Drive(later.option(), clear, null);
    }

    private Drive around(GeoPoint from, GeoPoint to, OffsetDateTime departure) {
        Set<Long> blocked = new HashSet<>();
        for (int round = 0; round <= MAX_DETOURS; round++) {
            Scenario scenario = scenario(from, to, departure, blocked);
            List<SuggestedOption> options;
            try {
                options = engine.run(scenario).options();
            } catch (NoRouteException e) {
                return new Drive(null, null, blocked.isEmpty() ? DriveProblem.NO_ROUTE : DriveProblem.BLOCKED);
            }
            if (!options.isEmpty()) {
                return new Drive(options.getFirst(), null, null);
            }
            Set<Long> found = closures(from, to, departure, blocked);
            if (found == null) {
                return new Drive(null, null, blocked.isEmpty() ? DriveProblem.NO_ROUTE : DriveProblem.BLOCKED);
            }
            if (!blocked.addAll(found)) {
                break;
            }
        }
        return new Drive(null, null, DriveProblem.BLOCKED);
    }

    /** Segments of the closures the fastest way (around {@code blocked}) passes, {@code null} without a way. */
    private @Nullable Set<Long> closures(GeoPoint from, GeoPoint to, OffsetDateTime departure, Set<Long> blocked) {
        RouteResult direct;
        try {
            direct = routing.route(List.of(stop(from), stop(to)), RouteOptions.blocked(blocked));
        } catch (NoRouteException e) {
            return null;
        }
        Set<Long> ids = direct.segments().stream().map(s -> s.segmentId()).collect(Collectors.toSet());
        List<ActiveIncident> incidents = activeIncidents.onSegments(ids, departure);
        CandidateEvaluator.Outcome outcome = CandidateEvaluator.evaluate(ViaPointExtractor.steps(direct.segments()),
                departure, RouteOptions.none(), incidents);
        Set<Long> closedIncidents = outcome.conflicts().stream().filter(c -> c.impact() == Impact.BLOCKED)
                .map(SuggestedOption.Conflict::incidentId).collect(Collectors.toSet());
        return incidents.stream().filter(i -> closedIncidents.contains(i.id()))
                .flatMap(i -> i.segmentIds().stream()).collect(Collectors.toSet());
    }

    /** When the closures on the direct way have all ended, {@code null} if one has no end. */
    private @Nullable OffsetDateTime clearAt(GeoPoint from, GeoPoint to, OffsetDateTime departure) {
        RouteResult direct;
        try {
            direct = routing.route(List.of(stop(from), stop(to)), RouteOptions.none());
        } catch (NoRouteException e) {
            return null;
        }
        Set<Long> ids = direct.segments().stream().map(s -> s.segmentId()).collect(Collectors.toSet());
        List<ActiveIncident> incidents = activeIncidents.onSegments(ids, departure);
        CandidateEvaluator.Outcome outcome = CandidateEvaluator.evaluate(ViaPointExtractor.steps(direct.segments()),
                departure, RouteOptions.none(), incidents);
        Set<Long> closed = outcome.conflicts().stream().filter(c -> c.impact() == Impact.BLOCKED)
                .map(SuggestedOption.Conflict::incidentId).collect(Collectors.toSet());
        List<ActiveIncident> closures = incidents.stream().filter(i -> closed.contains(i.id())).toList();
        if (closures.isEmpty() || closures.stream().anyMatch(i -> i.validTo() == null)) {
            return null;
        }
        return closures.stream().map(ActiveIncident::validTo).filter(Objects::nonNull)
                .max(OffsetDateTime::compareTo).map(end -> end.plusMinutes(15)).orElse(null);
    }

    private static Scenario scenario(GeoPoint from, GeoPoint to, OffsetDateTime departure, Set<Long> blocked) {
        // no route of the trip: id 0 only names it in logs
        return new Scenario(0L, SuggestionTrigger.INCIDENT, null, stop(from), stop(to), List.of(),
                WaypointMode.IGNORE, departure, RouteOptions.blocked(Set.copyOf(blocked)), List.of(),
                Set.of(OptionKind.ALTERNATIVES), 1, List.of(), null);
    }

    private static RoutePoint stop(GeoPoint point) {
        return new RoutePoint(point, RoutePoint.Kind.STOP, null);
    }
}
