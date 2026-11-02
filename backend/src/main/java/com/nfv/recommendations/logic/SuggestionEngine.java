package com.nfv.recommendations.logic;

import com.nfv.incidents.logic.ActiveIncident;
import com.nfv.incidents.logic.ActiveIncidents;
import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.network.logic.SegmentQueries;
import com.nfv.recommendations.entities.RouteOption;
import com.nfv.trips.logic.PlannedSection;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Scenario → candidates of the generators → evaluation → ranking → via points, see Docs/recommendations.md#ablauf.
 * Routes outside of transactions, nothing is stored here.
 */
@Component
@RequiredArgsConstructor
class SuggestionEngine {
    private static final GeometryFactory WGS84 = new GeometryFactory(new PrecisionModel(), 4326);

    private final List<CandidateGenerator> generators;
    private final SegmentQueries segmentQueries;
    private final ActiveIncidents activeIncidents;
    private final SuggestionRanker ranker;
    private final ViaPointExtractor viaPointExtractor;

    /**
     * @param options   the suggestions, best first
     * @param reference the planned route evaluated the same way, {@code null} if not planned
     */
    record Result(List<RouteOption.Values> options, @Nullable CandidateEvaluator.Outcome reference) {
    }

    /**
     * @throws com.nfv.network.logic.NoRouteException            no way between the points
     * @throws com.nfv.network.logic.RoutingUnavailableException no graph yet
     */
    Result run(Scenario scenario) {
        List<Candidate> candidates = generators.stream()
                .filter(generator -> scenario.generators().contains(generator.kind()))
                .flatMap(generator -> generator.generate(scenario).stream())
                .toList();
        Set<Long> segmentIds = new HashSet<>();
        candidates.forEach(c -> c.segments().forEach(s -> segmentIds.add(s.segmentId())));
        scenario.reference().forEach(s -> segmentIds.add(s.segmentId()));
        Map<Long, SegmentInfo> segments = segmentQueries.segments(segmentIds);
        List<ActiveIncident> incidents = activeIncidents.onSegments(segmentIds, scenario.departure());

        CandidateEvaluator.Outcome reference = reference(scenario, segments, incidents);
        if (candidates.isEmpty()) {
            return new Result(List.of(), reference);
        }
        Set<Long> referenceIds = scenario.reference().stream().map(PlannedSection::segmentId)
                .collect(Collectors.toSet());
        List<SuggestionRanker.Evaluated> evaluated = candidates.stream()
                .map(candidate -> {
                    List<CandidateEvaluator.Step> steps = ViaPointExtractor.steps(candidate.segments());
                    return new SuggestionRanker.Evaluated(candidate, CandidateEvaluator.evaluate(steps,
                            candidate.departure(), scenario.constraints(), incidents),
                            CandidateEvaluator.sharedRatio(steps, referenceIds));
                })
                .toList();
        Candidate base = candidates.getFirst();
        List<RouteOption.Values> options = new ArrayList<>();
        for (SuggestionRanker.Ranked ranked : ranker.rank(evaluated, scenario.count(), reference, base, segments)) {
            Candidate candidate = ranked.evaluated().candidate();
            CandidateEvaluator.Outcome outcome = ranked.evaluated().outcome();
            options.add(new RouteOption.Values(candidate.kind(), ranked.labels(), ranked.via(),
                    outcome.departure(), outcome.arrival(), outcome.distanceMeters(), outcome.duration(),
                    Duration.ZERO, outcome.delay(), ranked.evaluated().sharedRatio(), line(candidate.geometry()),
                    viaPointExtractor.extract(scenario, candidate, base, segments), outcome.conflicts()));
        }
        return new Result(options, reference);
    }

    /** Only the planned route, e.g. when a stored set is read again; incidents as of now. */
    @Nullable
    CandidateEvaluator.Outcome reference(Scenario scenario) {
        Set<Long> segmentIds = scenario.reference().stream().map(PlannedSection::segmentId)
                .collect(Collectors.toSet());
        return reference(scenario, segmentQueries.segments(segmentIds),
                activeIncidents.onSegments(segmentIds, scenario.departure()));
    }

    /** Planned route from the departure of the scenario, distances from the segment lengths. */
    @Nullable
    private static CandidateEvaluator.Outcome reference(Scenario scenario, Map<Long, SegmentInfo> segments,
                                                        List<ActiveIncident> incidents) {
        if (scenario.reference().isEmpty()) {
            return null;
        }
        List<CandidateEvaluator.Step> steps = scenario.reference().stream()
                .map(section -> {
                    SegmentInfo info = segments.get(section.segmentId());
                    double length = info == null ? 0 : info.lengthMeters();
                    return new CandidateEvaluator.Step(section.segmentId(),
                            length * (section.toFraction() - section.fromFraction()),
                            Duration.between(section.expectedPassageStart(), section.expectedPassageEnd()));
                })
                .toList();
        return CandidateEvaluator.evaluate(steps, scenario.departure(), scenario.constraints(), incidents);
    }

    static LineString line(List<GeoPoint> points) {
        Coordinate[] coordinates = points.stream().map(p -> new Coordinate(p.lon(), p.lat()))
                .toArray(Coordinate[]::new);
        if (coordinates.length == 1) {
            coordinates = new Coordinate[]{coordinates[0], coordinates[0]};
        }
        return WGS84.createLineString(coordinates);
    }
}