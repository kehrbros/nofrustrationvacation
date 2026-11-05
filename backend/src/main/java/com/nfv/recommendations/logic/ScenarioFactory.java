package com.nfv.recommendations.logic;

import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.RouteOptions;
import com.nfv.network.logic.RoutePoint;
import com.nfv.trips.logic.RouteSnapshot;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Builds the {@link Scenario}, the only part that depends on the trigger, see Docs/recommendations.md#szenario-recommendationslogic. */
final class ScenarioFactory {
    private ScenarioFactory() {
    }

    /** Scenario or why there is none. */
    record Built(@Nullable Scenario scenario, @Nullable SuggestionProblem problem) {
    }

    /**
     * Planning: no restrictions in the network, incidents are only evaluated. Without {@code includeWaypoints} only
     * between the stops; the planned route with waypoints stays the reference.
     *
     * @param departure departure to try instead of the one at the start stop
     */
    static Built forPlanning(RouteSnapshot route, int count, boolean includeWaypoints,
                             @Nullable OffsetDateTime departure) {
        if (!route.from().hasCoordinates() || !route.to().hasCoordinates()) {
            return new Built(null, SuggestionProblem.MISSING_COORDINATES);
        }
        OffsetDateTime start = departure != null ? departure : route.from().departureAt();
        if (start == null) {
            return new Built(null, SuggestionProblem.MISSING_DEPARTURE);
        }
        List<Scenario.Via> vias = includeWaypoints
                ? route.waypoints().stream().map(w -> new Scenario.Via(new RoutePoint(
                        new GeoPoint(w.latitude(), w.longitude()), RoutePoint.Kind.VIA, w.roadHint()), w.id()))
                .toList()
                : List.of();
        return new Built(new Scenario(route.routeId(), SuggestionTrigger.PLANNING, null, stop(route.from()),
                stop(route.to()), vias, includeWaypoints ? WaypointMode.FOLLOW : WaypointMode.IGNORE, start,
                RouteOptions.none(), route.sections(), Set.of(OptionKind.ALTERNATIVES), count), null);
    }

    private static RoutePoint stop(RouteSnapshot.StopPoint stop) {
        return new RoutePoint(new GeoPoint(Objects.requireNonNull(stop.latitude()),
                Objects.requireNonNull(stop.longitude())), RoutePoint.Kind.STOP, null);
    }
}