package com.nfv.recommendations.logic;

import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.NoRouteException;
import com.nfv.network.logic.RouteOptions;
import com.nfv.network.logic.RoutePoint;
import com.nfv.network.logic.RouteResult;
import com.nfv.network.logic.RoutedSegment;
import com.nfv.network.logic.Routing;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.recommendations.config.RecommendationProperties;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;

/**
 * Via points that reproduce a candidate when they become waypoints: the intention is stored, not the segments, see
 * Docs/recommendations.md#auswahl-speichern.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ViaPointExtractor {
    private final Routing routing;
    private final RecommendationProperties properties;

    /** Point with its place along the candidate: index into its segments, user waypoints between two indices. */
    private record Placed(double order, SuggestedOption.Point point) {
    }

    /**
     * With {@code FOLLOW} the waypoints of the user stay. For every part that differs from {@code base} (the fastest
     * combination, planned without waypoints) a point in the middle of its longest segment; more points in the longest
     * part still different until {@code viaReproduceRatio} is reached. Every point is checked by routing over all
     * points the way {@code RoutePlanning} plans after choosing: a point that cannot be routed is replaced by the next
     * longest segment of its part, so the returned points can always be planned (unless the waypoints of the user
     * cannot), see Docs/recommendations.md#auswahl-speichern.
     *
     * @param base     see {@link #base}
     * @param segments details of all segments of the candidate
     */
    List<SuggestedOption.Point> extract(Scenario scenario, Candidate candidate, Candidate base,
                                    Map<Long, SegmentInfo> segments) {
        List<RoutedSegment> all = candidate.segments();
        List<Placed> placed = new ArrayList<>();
        List<List<Integer>> parts = new ArrayList<>();
        int offset = 0;
        for (int leg = 0; leg < candidate.legs().size(); leg++) {
            List<RoutedSegment> legSegments = candidate.legs().get(leg).segments();
            Set<Long> baseIds = ids(base.legs().get(leg));
            int legOffset = offset;
            runs(legSegments, baseIds::contains)
                    .forEach(run -> parts.add(run.stream().map(i -> legOffset + i).toList()));
            offset += legSegments.size();
            if (leg < scenario.vias().size()) {
                Scenario.Via via = scenario.vias().get(leg);
                placed.add(new Placed(offset - 0.5, new SuggestedOption.Point(via.point().point().lat(),
                        via.point().point().lon(), via.point().roadHint(), via.waypointId())));
            }
        }
        if (parts.isEmpty()) {
            return points(placed);
        }
        Set<Long> reproduced = routedIds(scenario, placed);
        if (reproduced == null) {
            log.warn("waypoints of route {} not routable, suggestion keeps them without via points",
                    scenario.routeId());
            return points(placed);
        }
        Set<Integer> used = new HashSet<>();
        int limit = parts.size() * properties.maxViaPointsPerSection();
        for (List<Integer> part : parts) {
            reproduced = placeChecked(scenario, part, placed, used, limit, all, segments, reproduced);
        }
        while (used.size() < limit) {
            Double ratio = CandidateEvaluator.sharedRatio(steps(all), reproduced);
            if (ratio == null || ratio >= properties.viaReproduceRatio()) {
                break;
            }
            Set<Long> current = reproduced;
            List<Integer> worst = runs(all, current::contains).stream()
                    .filter(run -> run.stream().anyMatch(i -> !used.contains(i)))
                    .max(Comparator.comparingDouble(run -> length(all, run)))
                    .orElse(null);
            if (worst == null) {
                break;
            }
            reproduced = placeChecked(scenario, worst, placed, used, limit, all, segments, reproduced);
        }
        Double ratio = CandidateEvaluator.sharedRatio(steps(all), reproduced);
        if (ratio != null && ratio < properties.viaReproduceRatio()) {
            log.warn("via points of a suggestion on route {} reproduce only {} of it", scenario.routeId(), ratio);
        }
        return points(placed);
    }

    /**
     * Adds a point on the longest unused segment of the run that keeps all points routable, trying the next longest
     * while routing fails: at most {@code maxViaPointsPerSection} segments of the run per call and until {@code limit}
     * segments are used in total.
     *
     * @return segments routed over the points, unchanged if no segment of the run worked
     */
    private Set<Long> placeChecked(Scenario scenario, List<Integer> run, List<Placed> placed, Set<Integer> used,
                                   int limit, List<RoutedSegment> all, Map<Long, SegmentInfo> segments,
                                   Set<Long> reproduced) {
        for (int tries = 0; tries < properties.maxViaPointsPerSection() && used.size() < limit
                && run.stream().anyMatch(i -> !used.contains(i)); tries++) {
            int index = longest(all, run, used);
            used.add(index);
            SegmentInfo info = segments.get(all.get(index).segmentId());
            if (info == null) {
                continue;
            }
            String hint = info.ref() != null ? info.ref() : info.name();
            Placed point = new Placed(index, new SuggestedOption.Point(info.midpoint().lat(), info.midpoint().lon(),
                    hint, null));
            placed.add(point);
            Set<Long> routed = routedIds(scenario, placed);
            if (routed != null) {
                return routed;
            }
            placed.remove(point);
        }
        return reproduced;
    }

    /**
     * Way the points have to steer away from. Choosing plans the route without incidents, so with an incident this is
     * the way without restrictions: otherwise the best way around an incident would get no point and the plan would
     * lead through the incident again.
     */
    Candidate base(Scenario scenario, List<Candidate> candidates) {
        if (scenario.trigger() != SuggestionTrigger.INCIDENT) {
            return candidates.getFirst();
        }
        List<RoutePoint> points = scenario.points();
        List<RouteResult> legs = new ArrayList<>();
        for (int i = 0; i + 1 < points.size(); i++) {
            legs.add(routing.route(List.of(asStop(points.get(i)), asStop(points.get(i + 1))), RouteOptions.none()));
        }
        return new Candidate(candidates.getFirst().kind(), legs, scenario.departure());
    }

    /** The waypoints as they are, for a candidate on the planned route. */
    List<SuggestedOption.Point> waypoints(Scenario scenario) {
        return scenario.vias().stream().map(via -> new SuggestedOption.Point(via.point().point().lat(),
                via.point().point().lon(), via.point().roadHint(), via.waypointId())).toList();
    }

    private static RoutePoint asStop(RoutePoint point) {
        return new RoutePoint(point.point(), RoutePoint.Kind.STOP, point.roadHint());
    }

    /** Segments of the route over the points in their order, {@code null} if it cannot be routed. */
    @Nullable
    private Set<Long> routedIds(Scenario scenario, List<Placed> placed) {
        List<RoutePoint> route = new ArrayList<>();
        route.add(scenario.start());
        points(placed).forEach(point -> route.add(new RoutePoint(new GeoPoint(point.latitude(), point.longitude()),
                RoutePoint.Kind.VIA, point.roadHint())));
        route.add(scenario.end());
        try {
            // without restrictions: that is how the route is planned after choosing
            return ids(routing.route(route, RouteOptions.none()));
        } catch (NoRouteException e) {
            log.debug("via points of a suggestion not routable: {}", e.getMessage());
            return null;
        }
    }

    /** Maximal runs of consecutive segment indices not matching {@code inOther}. */
    static List<List<Integer>> runs(List<RoutedSegment> segments, LongPredicate inOther) {
        List<List<Integer>> runs = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            if (inOther.test(segments.get(i).segmentId())) {
                if (!current.isEmpty()) {
                    runs.add(current);
                    current = new ArrayList<>();
                }
            } else {
                current.add(i);
            }
        }
        if (!current.isEmpty()) {
            runs.add(current);
        }
        return runs;
    }

    /** Index of the longest segment of the run that is not used yet. */
    private static int longest(List<RoutedSegment> segments, List<Integer> run, Set<Integer> used) {
        return run.stream().filter(i -> !used.contains(i))
                .max(Comparator.comparingDouble(i -> segments.get(i).distanceMeters()))
                .orElse(run.getFirst());
    }

    private static double length(List<RoutedSegment> segments, List<Integer> run) {
        return run.stream().mapToDouble(i -> segments.get(i).distanceMeters()).sum();
    }

    private static List<SuggestedOption.Point> points(List<Placed> placed) {
        return placed.stream().sorted(Comparator.comparingDouble(Placed::order)).map(Placed::point).toList();
    }

    private static Set<Long> ids(RouteResult result) {
        return result.segments().stream().map(RoutedSegment::segmentId).collect(Collectors.toSet());
    }

    static List<CandidateEvaluator.Step> steps(List<RoutedSegment> segments) {
        return segments.stream()
                .map(s -> new CandidateEvaluator.Step(s.segmentId(), s.distanceMeters(), s.duration()))
                .toList();
    }
}