package com.nfv.recommendations.logic;

import com.nfv.incidents.logic.ActiveIncident;
import com.nfv.incidents.logic.ActiveIncidents;
import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.network.logic.SegmentQueries;
import com.nfv.trips.logic.PlannedSection;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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
    private final List<CandidateGenerator> generators;
    private final SegmentQueries segmentQueries;
    private final ActiveIncidents activeIncidents;
    private final SuggestionRanker ranker;
    private final ViaPointExtractor viaPointExtractor;

    /**
     * @param options   the suggestions, best first
     * @param reference the planned route evaluated the same way, {@code null} if not planned
     */
    record Result(List<SuggestedOption> options, @Nullable CandidateEvaluator.Outcome reference) {
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
        // also incidents that end before the planned departure, an earlier one may pass them
        OffsetDateTime from = candidates.stream().map(Candidate::departure).min(Comparator.naturalOrder())
                .filter(earliest -> earliest.isBefore(scenario.departure())).orElse(scenario.departure());
        List<ActiveIncident> incidents = activeIncidents.onSegments(segmentIds, from);

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
                            candidate.departure(), scenario.evaluationConstraints(), incidents),
                            CandidateEvaluator.sharedRatio(steps, referenceIds));
                })
                .toList();
        Candidate base = candidates.getFirst();
        Candidate viaBase = viaPointExtractor.base(scenario, candidates);
        List<SuggestedOption> options = new ArrayList<>();
        for (SuggestionRanker.Ranked ranked : ranker.rank(evaluated, scenario.count(), reference, base, segments)) {
            Candidate candidate = ranked.evaluated().candidate();
            CandidateEvaluator.Outcome outcome = ranked.evaluated().outcome();
            options.add(new SuggestedOption(options.size() + 1, candidate.kind(), ranked.labels(), ranked.via(),
                    outcome.departure(), outcome.arrival(), outcome.distanceMeters(), outcome.duration(),
                    Duration.ZERO, outcome.delay(), ranked.evaluated().sharedRatio(), line(candidate.geometry()),
                    candidate.kind() == OptionKind.DEPARTURE_SHIFT ? viaPointExtractor.waypoints(scenario)
                            : viaPointExtractor.extract(scenario, candidate, viaBase, segments),
                    outcome.conflicts(), candidate.segments()));
        }
        return new Result(options, reference);
    }

    /** Planned route from the departure of the scenario. */
    @Nullable
    private static CandidateEvaluator.Outcome reference(Scenario scenario, Map<Long, SegmentInfo> segments,
                                                        List<ActiveIncident> incidents) {
        if (scenario.reference().isEmpty()) {
            return null;
        }
        return CandidateEvaluator.evaluate(steps(scenario.reference(), segments), scenario.departure(),
                scenario.evaluationConstraints(), incidents);
    }

    /** The planned sections as steps, distances from the segment lengths. */
    static List<CandidateEvaluator.Step> steps(List<PlannedSection> sections, Map<Long, SegmentInfo> segments) {
        return sections.stream()
                .map(section -> {
                    SegmentInfo info = segments.get(section.segmentId());
                    double length = info == null ? 0 : info.lengthMeters();
                    return new CandidateEvaluator.Step(section.segmentId(),
                            length * (section.toFraction() - section.fromFraction()),
                            Duration.between(section.expectedPassageStart(), section.expectedPassageEnd()));
                })
                .toList();
    }

    /** Line for the map, a single point twice so it stays a line. */
    static List<GeoPoint> line(List<GeoPoint> points) {
        return points.size() == 1 ? List.of(points.getFirst(), points.getFirst()) : points;
    }
}