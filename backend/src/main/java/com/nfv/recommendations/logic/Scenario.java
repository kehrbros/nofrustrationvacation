package com.nfv.recommendations.logic;

import com.nfv.network.logic.RoutePoint;
import com.nfv.network.logic.RouteOptions;
import com.nfv.trips.logic.PlannedSection;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Starting point of suggestions: points, departure, restrictions in the network and the planned route. Planning and
 * incidents only differ in how it is built ({@link ScenarioFactory}), see Docs/recommendations.md#szenario-recommendationslogic.
 *
 * @param vias              waypoints of the user, empty with {@code IGNORE}
 * @param constraints       blocked and delayed segments for routing, none when planning
 * @param reference         sections of the planned route still ahead, empty if not planned
 * @param count             suggestions wanted
 * @param avoid             periods a {@code DEPARTURE_SHIFT} should pass outside of, empty when planning
 * @param earliestDeparture no {@code DEPARTURE_SHIFT} before this, {@code null} when planning
 */
record Scenario(long routeId, SuggestionTrigger trigger, @Nullable Long consequenceId, RoutePoint start,
                RoutePoint end, List<Via> vias, WaypointMode waypoints, OffsetDateTime departure,
                RouteOptions constraints, List<PlannedSection> reference, Set<OptionKind> generators, int count,
                List<Avoid> avoid, @Nullable OffsetDateTime earliestDeparture) {

    /** Waypoint of the user the suggestions pass. */
    record Via(RoutePoint point, long waypointId) {
    }

    /**
     * Period in which the planned route should not pass a place.
     *
     * @param reachedAfter time from the departure until the place is reached
     * @param leftAfter    time from the departure until the place is left
     * @param until        {@code null}: until further notice
     */
    record Avoid(Duration reachedAfter, Duration leftAfter, OffsetDateTime from, @Nullable OffsetDateTime until) {
    }

    /**
     * Restrictions for the evaluation. With incidents the constraints are built from the incidents the evaluation
     * looks at anyway, with them too a delay would count twice and a closure at night block the whole day.
     */
    RouteOptions evaluationConstraints() {
        return trigger == SuggestionTrigger.INCIDENT ? RouteOptions.none() : constraints;
    }

    /** Start, vias and end in driving order. */
    List<RoutePoint> points() {
        return Stream.of(Stream.of(start), vias.stream().map(Via::point), Stream.of(end))
                .flatMap(Function.identity()).toList();
    }
}