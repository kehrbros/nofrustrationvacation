package com.nfv.recommendations.logic;

import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.logic.RouteConsequence;
import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.RouteOptions;
import com.nfv.network.logic.RoutePoint;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.trips.logic.PlannedSection;
import com.nfv.trips.logic.RouteSnapshot;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

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
                RouteOptions.none(), route.sections(), Set.of(OptionKind.ALTERNATIVES), count, List.of(), null), null);
    }

    /**
     * Incident: the waypoints stay, the incidents that count on the route become restrictions for routing. Once the
     * route has started, suggestions begin at the estimated position (middle of the section due now), waypoints
     * behind it are left out and the departure can no longer be shifted.
     *
     * @param chosen    the consequence asked about, counts even if only {@code MARGINAL}
     * @param all       all consequences of the route
     * @param kinds     generators wanted
     * @param departure departure to try instead of the planned one, ignored once the route has started
     * @param segments  details of the segments of the planned route
     */
    static Built forConsequence(RouteSnapshot route, RouteConsequence chosen, List<RouteConsequence> all,
                                Set<OptionKind> kinds, int count, @Nullable OffsetDateTime departure,
                                OffsetDateTime now, Map<Long, SegmentInfo> segments) {
        if (!route.from().hasCoordinates() || !route.to().hasCoordinates()) {
            return new Built(null, SuggestionProblem.MISSING_COORDINATES);
        }
        List<PlannedSection> sections = route.sections();
        if (chosen.over() || sections.isEmpty() || !sections.getLast().expectedPassageEnd().isAfter(now)) {
            return new Built(null, SuggestionProblem.CONSEQUENCE_PASSED);
        }
        OffsetDateTime plannedDeparture = sections.getFirst().expectedPassageStart();
        List<RouteConsequence> counting = all.stream().filter(c -> c.id() == chosen.id() || c.counts()).toList();
        boolean underway = !plannedDeparture.isAfter(now);
        RoutePoint start = stop(route.from());
        OffsetDateTime at = departure != null ? departure : plannedDeparture;
        List<PlannedSection> ahead = sections;
        int current = 0;
        if (underway) {
            int section = IntStream.range(0, sections.size())
                    .filter(i -> sections.get(i).expectedPassageEnd().isAfter(now)).findFirst().orElseThrow();
            current = section;
            SegmentInfo position = segments.get(sections.get(section).segmentId());
            if (position == null) {
                return new Built(null, SuggestionProblem.NO_ROUTE);
            }
            start = new RoutePoint(position.midpoint(), RoutePoint.Kind.STOP, null);
            at = now;
            ahead = sections.subList(section, sections.size());
        }
        int passed = current;
        List<Scenario.Via> vias = route.waypoints().stream()
                .filter(w -> !underway || nearestSection(w, sections, segments) > passed)
                .map(w -> new Scenario.Via(new RoutePoint(new GeoPoint(w.latitude(), w.longitude()),
                        RoutePoint.Kind.VIA, w.roadHint()), w.id()))
                .toList();
        Set<OptionKind> generators = EnumSet.copyOf(kinds);
        if (underway) {
            generators.remove(OptionKind.DEPARTURE_SHIFT);
        }
        OffsetDateTime arrivedAt = route.from().arrivalAt();
        return new Built(new Scenario(route.routeId(), SuggestionTrigger.INCIDENT, chosen.id(), start, stop(route.to()),
                vias, WaypointMode.FOLLOW, at, constraints(counting), ahead, generators, count,
                underway ? List.of() : avoid(counting, sections),
                arrivedAt != null && arrivedAt.isAfter(now) ? arrivedAt : now), null);
    }

    /** Closures are blocked; a delay is spread over the segments of its incident, the evaluation counts it once. */
    private static RouteOptions constraints(List<RouteConsequence> counting) {
        Set<Long> blocked = new HashSet<>();
        Map<Long, Duration> delayed = new HashMap<>();
        for (RouteConsequence consequence : counting) {
            if (consequence.impact() == Impact.BLOCKED) {
                blocked.addAll(consequence.segmentIds());
            } else if (consequence.delay() != null && consequence.delay().isPositive()) {
                Duration share = consequence.delay().dividedBy(consequence.segmentIds().size());
                consequence.segmentIds().forEach(id -> delayed.merge(id, share, Duration::plus));
            }
        }
        return new RouteOptions(blocked, delayed);
    }

    private static List<Scenario.Avoid> avoid(List<RouteConsequence> counting, List<PlannedSection> sections) {
        OffsetDateTime departure = sections.getFirst().expectedPassageStart();
        List<Scenario.Avoid> avoid = new ArrayList<>();
        for (RouteConsequence consequence : counting) {
            sections.stream().filter(s -> consequence.segmentIds().contains(s.segmentId())).findFirst()
                    .ifPresent(section -> avoid.add(new Scenario.Avoid(
                            Duration.between(departure, section.expectedPassageStart()),
                            Duration.between(departure, section.expectedPassageEnd()),
                            consequence.from(), consequence.until())));
        }
        return avoid;
    }

    /** Position of the section closest to a waypoint: sections do not record which waypoint they lead to. */
    private static int nearestSection(RouteSnapshot.WaypointPoint waypoint, List<PlannedSection> sections,
                                      Map<Long, SegmentInfo> segments) {
        double cosLat = Math.cos(Math.toRadians(waypoint.latitude()));
        return IntStream.range(0, sections.size())
                .filter(i -> segments.containsKey(sections.get(i).segmentId()))
                .boxed()
                .min(Comparator.comparingDouble(i -> {
                    GeoPoint midpoint = segments.get(sections.get(i).segmentId()).midpoint();
                    return Math.hypot((midpoint.lon() - waypoint.longitude()) * cosLat,
                            midpoint.lat() - waypoint.latitude());
                }))
                .orElse(Integer.MAX_VALUE);
    }

    private static RoutePoint stop(RouteSnapshot.StopPoint stop) {
        return new RoutePoint(new GeoPoint(Objects.requireNonNull(stop.latitude()),
                Objects.requireNonNull(stop.longitude())), RoutePoint.Kind.STOP, null);
    }
}