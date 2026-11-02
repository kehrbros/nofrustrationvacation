package com.nfv.recommendations.logic;

import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.NoRouteException;
import com.nfv.network.logic.RoutePoint;
import com.nfv.network.logic.RouteResult;
import com.nfv.network.logic.RoutedSegment;
import com.nfv.network.logic.Routing;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.recommendations.config.RecommendationProperties;
import com.nfv.recommendations.entities.RouteOption;
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
 * Docs/recommendations.md#übernehmen-eines-vorschlags.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class ViaPointExtractor {
    private final Routing routing;
    private final RecommendationProperties properties;

    /** Point with its place along the candidate: index into its segments, user waypoints between two indices. */
    private record Placed(double order, RouteOption.Point point) {
    }

    /**
     * With {@code FOLLOW} the waypoints of the user stay. For every part that differs from {@code base} (the fastest
     * combination) a point in the middle of its longest segment; checked by routing over the points, more points in
     * the longest part still different until {@code viaReproduceRatio} is reached.
     *
     * @param segments details of all segments of the candidate
     */
    List<RouteOption.Point> extract(Scenario scenario, Candidate candidate, Candidate base,
                                    Map<Long, SegmentInfo> segments) {
        List<RoutedSegment> all = candidate.segments();
        List<Placed> placed = new ArrayList<>();
        Set<Integer> used = new HashSet<>();
        int offset = 0;
        int runs = 0;
        for (int leg = 0; leg < candidate.legs().size(); leg++) {
            List<RoutedSegment> legSegments = candidate.legs().get(leg).segments();
            Set<Long> baseIds = ids(base.legs().get(leg));
            for (List<Integer> run : runs(legSegments, baseIds::contains)) {
                runs++;
                int index = offset + longest(legSegments, run, Set.of());
                place(placed, used, all, index, segments);
            }
            offset += legSegments.size();
            if (leg < scenario.vias().size()) {
                Scenario.Via via = scenario.vias().get(leg);
                placed.add(new Placed(offset - 0.5, new RouteOption.Point(via.point().point().lat(),
                        via.point().point().lon(), via.point().roadHint(), via.waypointId())));
            }
        }
        if (runs == 0) {
            return points(placed);
        }
        int limit = runs * properties.maxViaPointsPerSection();
        while (used.size() < limit) {
            Set<Long> reproduced;
            try {
                reproduced = routedIds(scenario, points(placed));
            } catch (NoRouteException e) {
                log.debug("via points of a suggestion not routable: {}", e.getMessage());
                break;
            }
            Double ratio = CandidateEvaluator.sharedRatio(steps(all), reproduced);
            if (ratio == null || ratio >= properties.viaReproduceRatio()) {
                break;
            }
            List<List<Integer>> missing = runs(all, reproduced::contains);
            List<Integer> worst = missing.stream()
                    .filter(run -> run.stream().anyMatch(i -> !used.contains(i)))
                    .max(Comparator.comparingDouble(run -> length(all, run)))
                    .orElse(null);
            if (worst == null) {
                break;
            }
            place(placed, used, all, longest(all, worst, used), segments);
        }
        return points(placed);
    }

    private static void place(List<Placed> placed, Set<Integer> used, List<RoutedSegment> all, int index,
                              Map<Long, SegmentInfo> segments) {
        used.add(index);
        SegmentInfo info = segments.get(all.get(index).segmentId());
        if (info == null) {
            return;
        }
        String hint = info.ref() != null ? info.ref() : info.name();
        placed.add(new Placed(index, new RouteOption.Point(info.midpoint().lat(), info.midpoint().lon(), hint, null)));
    }

    private Set<Long> routedIds(Scenario scenario, List<RouteOption.Point> points) {
        List<RoutePoint> route = new ArrayList<>();
        route.add(scenario.start());
        points.forEach(point -> route.add(new RoutePoint(new GeoPoint(point.latitude(), point.longitude()),
                RoutePoint.Kind.VIA, point.roadHint())));
        route.add(scenario.end());
        return ids(routing.route(route, scenario.constraints()));
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

    private static List<RouteOption.Point> points(List<Placed> placed) {
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