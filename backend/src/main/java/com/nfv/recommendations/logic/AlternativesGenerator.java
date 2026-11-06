package com.nfv.recommendations.logic;

import com.nfv.network.logic.AlternativeOptions;
import com.nfv.network.logic.NoRouteException;
import com.nfv.network.logic.RouteOptions;
import com.nfv.network.logic.RoutePoint;
import com.nfv.network.logic.RouteResult;
import com.nfv.network.logic.Routing;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.network.logic.SegmentQueries;
import com.nfv.recommendations.config.RecommendationProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Alternatives from GraphHopper {@code alternative_route}, which only takes two points: one request per leg, then the
 * legs are combined and the best kept, see Docs/recommendations.md#generatoren.
 */
@Component
@RequiredArgsConstructor
class AlternativesGenerator implements CandidateGenerator {
    private final Routing routing;
    private final SegmentQueries segmentQueries;
    private final RecommendationProperties properties;

    @Override
    public OptionKind kind() {
        return OptionKind.ALTERNATIVES;
    }

    @Override
    public List<Candidate> generate(Scenario scenario) {
        // one in reserve, in case a suggestion drops out in the evaluation
        int keep = scenario.count() + 1;
        AlternativeOptions options = new AlternativeOptions(keep, properties.maxWeightFactor(),
                properties.maxShareFactor());
        List<RoutePoint> points = scenario.points();
        List<List<RouteResult>> legs = new ArrayList<>();
        try {
            for (int i = 0; i + 1 < points.size(); i++) {
                legs.add(leg(scenario, asStop(points.get(i)), asStop(points.get(i + 1)), options));
            }
        } catch (NoRouteException e) {
            if (scenario.trigger() != SuggestionTrigger.INCIDENT) {
                throw e;
            }
            // no way around the incident: the caller reports it, the planned route stays as comparison
            return List.of();
        }
        return combine(legs, keep).stream()
                .map(combination -> new Candidate(OptionKind.ALTERNATIVES, combination, scenario.departure()))
                .toList();
    }

    /** With an incident only legs that pass it get alternatives, the others stay the way they are driven now. */
    private List<RouteResult> leg(Scenario scenario, RoutePoint from, RoutePoint to, AlternativeOptions options) {
        if (scenario.trigger() == SuggestionTrigger.INCIDENT) {
            RouteResult direct = routing.route(List.of(from, to), RouteOptions.none());
            if (direct.segments().stream().noneMatch(segment -> restricted(scenario.constraints(), segment.segmentId()))) {
                return List.of(direct);
            }
        }
        return routing.alternatives(from, to, scenario.constraints(), options);
    }

    private static boolean restricted(RouteOptions constraints, long segmentId) {
        return constraints.blockedSegmentIds().contains(segmentId) || constraints.delayedSegments().containsKey(segmentId);
    }

    /**
     * Combinations of one alternative per leg, fastest first, at most {@code keep}. Leg by leg only the best
     * {@code keep} go on. Combinations that turn at a via point are dropped (no {@code pass_through} in
     * {@code alternative_route}), unless all of them turn there: a via point on a dead end can only be left the way
     * it was reached, {@code Routing.route} turns there as well.
     */
    List<List<RouteResult>> combine(List<List<RouteResult>> legs, int keep) {
        Map<Long, SegmentInfo> boundaries = segmentQueries.segments(boundarySegments(legs));
        List<List<RouteResult>> combinations = legs.getFirst().stream().limit(keep).map(List::of).toList();
        for (List<RouteResult> leg : legs.subList(1, legs.size())) {
            List<List<RouteResult>> straight = new ArrayList<>();
            List<List<RouteResult>> turning = new ArrayList<>();
            for (List<RouteResult> combination : combinations) {
                for (RouteResult alternative : leg) {
                    (turns(combination.getLast(), alternative, boundaries) ? turning : straight)
                            .add(Stream.concat(combination.stream(), Stream.of(alternative)).toList());
                }
            }
            combinations = (straight.isEmpty() ? turning : straight).stream()
                    .sorted(Comparator.comparing(AlternativesGenerator::duration))
                    .limit(keep)
                    .toList();
        }
        return combinations;
    }

    /** Whether the next leg starts in the opposite direction of the segment the previous one ends on. */
    static boolean turns(RouteResult previous, RouteResult next, Map<Long, SegmentInfo> segments) {
        if (previous.segments().isEmpty() || next.segments().isEmpty()) {
            return false;
        }
        SegmentInfo last = segments.get(previous.segments().getLast().segmentId());
        return last != null && Objects.equals(last.reverseId(), next.segments().getFirst().segmentId());
    }

    private static Set<Long> boundarySegments(List<List<RouteResult>> legs) {
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i + 1 < legs.size(); i++) {
            legs.get(i).stream().filter(result -> !result.segments().isEmpty())
                    .forEach(result -> ids.add(result.segments().getLast().segmentId()));
        }
        return ids;
    }

    private static Duration duration(List<RouteResult> combination) {
        return combination.stream().map(RouteResult::duration).reduce(Duration.ZERO, Duration::plus);
    }

    private static RoutePoint asStop(RoutePoint point) {
        return new RoutePoint(point.point(), RoutePoint.Kind.STOP, point.roadHint());
    }
}