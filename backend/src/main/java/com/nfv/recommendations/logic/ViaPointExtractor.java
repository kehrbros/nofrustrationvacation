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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongPredicate;
import java.util.stream.Collectors;

/**
 * Via points that reproduce a candidate when they become waypoints: the intention is stored, not the segments, see
 * Docs/recommendations.md#saving-the-choice.
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
     * combination, planned without waypoints) a point near the middle of the part, then more points in the middle of
     * the longest stretch the way over the points still misses, until that way {@linkplain Fit#good fits} the
     * candidate. Every point is checked by routing over all points the way {@code RoutePlanning} plans after choosing:
     * a point is kept only if the way can be planned and fits better than without it, otherwise the next segment of the
     * stretch is tried. So the returned points can always be planned (unless the waypoints of the user cannot), and a
     * point that would be reached on the old way and left again (a spur) is not kept, see
     * Docs/recommendations.md#saving-the-choice.
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
            Set<Long> baseIds = ids(base.legs().get(leg).segments());
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
        List<RoutedSegment> routed = routed(scenario, placed);
        if (routed == null) {
            log.warn("waypoints of route {} not routable, suggestion keeps them without via points",
                    scenario.routeId());
            return points(placed);
        }
        Set<Long> candidateIds = ids(all);
        Fit fit = Fit.of(all, candidateIds, routed);
        Set<Integer> tried = new HashSet<>();
        int limit = Math.min(properties.maxViaPoints(), properties.maxChosenPoints() - scenario.vias().size());
        // each try is a routing over all points
        int budget = 2 * limit * properties.maxViaPointsPerSection();
        int added = 0;
        for (List<Integer> part : parts) {
            if (added >= limit) {
                break;
            }
            Fit better = placeChecked(scenario, part, placed, tried, all, candidateIds, segments, fit);
            if (better != null) {
                fit = better;
                added++;
            }
        }
        while (added < limit && tried.size() < budget && !fit.good(properties)) {
            Set<Long> current = fit.routedIds();
            List<Integer> worst = runs(all, current::contains).stream()
                    .filter(run -> run.stream().anyMatch(i -> !tried.contains(i)))
                    .max(Comparator.comparingDouble(run -> length(all, run)))
                    .orElse(null);
            if (worst == null) {
                break;
            }
            Fit better = placeChecked(scenario, worst, placed, tried, all, candidateIds, segments, fit);
            if (better != null) {
                fit = better;
                added++;
            }
        }
        if (!fit.good(properties)) {
            log.warn("via points of a suggestion on route {} reproduce only {} of it, {} of the way over them lies "
                            + "on it, longest stretch missed {} m, longest stretch off it {} m", scenario.routeId(),
                    fit.covered(), fit.onCandidate(), Math.round(fit.longestMissing()), Math.round(fit.longestExtra()));
        }
        return points(placed);
    }

    /**
     * How well the way over the points reproduces the candidate.
     *
     * @param covered        share of the candidate (by distance) the way drives
     * @param onCandidate    share of the way (by distance) that lies on the candidate
     * @param longestMissing longest stretch of the candidate the way does not drive
     * @param longestExtra   longest stretch of the way off the candidate, e.g. to a via point and back
     * @param penalty        distance missed plus distance off the candidate, a point must lower it
     * @param routedIds      segments of the way
     */
    record Fit(double covered, double onCandidate, double longestMissing, double longestExtra, double penalty,
               Set<Long> routedIds) {

        static Fit of(List<RoutedSegment> candidate, Set<Long> candidateIds, List<RoutedSegment> routed) {
            Set<Long> routedIds = ids(routed);
            double candidateLength = candidate.stream().mapToDouble(RoutedSegment::distanceMeters).sum();
            double routedLength = routed.stream().mapToDouble(RoutedSegment::distanceMeters).sum();
            double missing = 0;
            for (RoutedSegment segment : candidate) {
                if (!routedIds.contains(segment.segmentId())) {
                    missing += segment.distanceMeters();
                }
            }
            double extra = 0;
            for (RoutedSegment segment : routed) {
                if (!candidateIds.contains(segment.segmentId())) {
                    extra += segment.distanceMeters();
                }
            }
            double longestMissing = runs(candidate, routedIds::contains).stream()
                    .mapToDouble(run -> length(candidate, run)).max().orElse(0);
            double longestExtra = runs(routed, candidateIds::contains).stream()
                    .mapToDouble(run -> length(routed, run)).max().orElse(0);
            return new Fit(candidateLength == 0 ? 1 : 1 - missing / candidateLength,
                    routedLength == 0 ? 1 : 1 - extra / routedLength, longestMissing, longestExtra, missing + extra,
                    routedIds);
        }

        /** Planned over the points, the route follows the candidate: no long stretch missed or added. */
        boolean good(RecommendationProperties properties) {
            return covered >= properties.viaReproduceRatio() && onCandidate >= properties.viaReproduceRatio()
                    && longestMissing <= properties.viaMaxGapMeters() && longestExtra <= properties.viaMaxGapMeters();
        }
    }

    /**
     * Adds a point on a segment of the run, the one nearest to the middle of the run first, that keeps all points
     * routable and makes the way fit better: at most {@code maxViaPointsPerSection} segments of the run per call.
     *
     * @return the fit with the new point, {@code null} if no segment of the run helped (no point is added)
     */
    private @Nullable Fit placeChecked(Scenario scenario, List<Integer> run, List<Placed> placed, Set<Integer> tried,
                                       List<RoutedSegment> all, Set<Long> candidateIds,
                                       Map<Long, SegmentInfo> segments, Fit fit) {
        List<Integer> order = byDistanceToMiddle(all, run);
        int tries = 0;
        for (int index : order) {
            if (tries >= properties.maxViaPointsPerSection()) {
                break;
            }
            if (!tried.add(index)) {
                continue;
            }
            SegmentInfo info = segments.get(all.get(index).segmentId());
            if (info == null) {
                continue;
            }
            tries++;
            String hint = info.ref() != null ? info.ref() : info.name();
            Placed point = new Placed(index, new SuggestedOption.Point(info.midpoint().lat(), info.midpoint().lon(),
                    hint, null));
            placed.add(point);
            List<RoutedSegment> routed = routed(scenario, placed);
            if (routed != null) {
                Fit with = Fit.of(all, candidateIds, routed);
                if (with.penalty() < fit.penalty()) {
                    return with;
                }
            }
            placed.remove(point);
        }
        return null;
    }

    /**
     * Indices of the run, the segment that contains the middle of the run (by distance) first, then outwards. Very
     * short segments (below a tenth of the longest of the run) come last, a point on them may snap to a crossing road.
     */
    static List<Integer> byDistanceToMiddle(List<RoutedSegment> segments, List<Integer> run) {
        double total = length(segments, run);
        double longest = run.stream().mapToDouble(i -> segments.get(i).distanceMeters()).max().orElse(0);
        Map<Integer, Double> centre = new HashMap<>();
        double along = 0;
        for (int i : run) {
            double length = segments.get(i).distanceMeters();
            centre.put(i, Math.abs(along + length / 2 - total / 2));
            along += length;
        }
        return run.stream()
                .sorted(Comparator.<Integer, Boolean>comparing(i -> segments.get(i).distanceMeters() < longest / 10)
                        .thenComparingDouble(centre::get))
                .toList();
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
    private List<RoutedSegment> routed(Scenario scenario, List<Placed> placed) {
        List<RoutePoint> route = new ArrayList<>();
        route.add(scenario.start());
        points(placed).forEach(point -> route.add(new RoutePoint(new GeoPoint(point.latitude(), point.longitude()),
                RoutePoint.Kind.VIA, point.roadHint())));
        route.add(scenario.end());
        try {
            // without restrictions: that is how the route is planned after choosing
            return routing.route(route, RouteOptions.none()).segments();
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

    private static double length(List<RoutedSegment> segments, List<Integer> run) {
        return run.stream().mapToDouble(i -> segments.get(i).distanceMeters()).sum();
    }

    private static List<SuggestedOption.Point> points(List<Placed> placed) {
        return placed.stream().sorted(Comparator.comparingDouble(Placed::order)).map(Placed::point).toList();
    }

    private static Set<Long> ids(List<RoutedSegment> segments) {
        return segments.stream().map(RoutedSegment::segmentId).collect(Collectors.toSet());
    }

    static List<CandidateEvaluator.Step> steps(List<RoutedSegment> segments) {
        return segments.stream()
                .map(s -> new CandidateEvaluator.Step(s.segmentId(), s.distanceMeters(), s.duration()))
                .toList();
    }
}