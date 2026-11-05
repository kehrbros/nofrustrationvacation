package com.nfv.recommendations.logic;

import com.nfv.network.logic.RoutePoint;
import com.nfv.network.logic.RouteOptions;
import com.nfv.trips.logic.PlannedSection;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Starting point of suggestions: points, departure, restrictions in the network and the planned route. Planning and
 * incidents only differ in how it is built ({@link ScenarioFactory}), see Docs/recommendations.md#szenario-recommendationslogic.
 *
 * @param vias        waypoints of the user, empty with {@code IGNORE}
 * @param constraints blocked and delayed segments, none when planning
 * @param reference   sections of the planned route, empty if not planned
 * @param count       suggestions wanted
 */
record Scenario(long routeId, SuggestionTrigger trigger, @Nullable Long consequenceId, RoutePoint start,
                RoutePoint end, List<Via> vias, WaypointMode waypoints, OffsetDateTime departure,
                RouteOptions constraints, List<PlannedSection> reference, Set<OptionKind> generators, int count) {

    /** Waypoint of the user the suggestions pass. */
    record Via(RoutePoint point, long waypointId) {
    }

    /** Start, vias and end in driving order. */
    List<RoutePoint> points() {
        return Stream.of(Stream.of(start), vias.stream().map(Via::point), Stream.of(end))
                .flatMap(Function.identity()).toList();
    }
}