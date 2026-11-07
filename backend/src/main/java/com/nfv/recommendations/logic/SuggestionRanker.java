package com.nfv.recommendations.logic;

import com.nfv.network.logic.RoutedSegment;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.recommendations.config.RecommendationProperties;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Drops, ranks and labels evaluated candidates, see Docs/recommendations.md#bewertung-aussortieren-ranking. */
@Component
@RequiredArgsConstructor
class SuggestionRanker {
    private final RecommendationProperties properties;

    record Evaluated(Candidate candidate, CandidateEvaluator.Outcome outcome, @Nullable Double sharedRatio) {
    }

    record Ranked(Evaluated evaluated, Set<OptionLabel> labels, @Nullable String via) {
    }

    /**
     * Without closures at the time of passage, by arrival then distance, at most {@code count}. Combined legs that
     * share too much with a better one are dropped (GraphHopper only checks single legs).
     *
     * @param reference planned route, {@code null} if not planned
     * @param base      fastest candidate, the main road of the part that differs from it becomes {@code via}
     */
    List<Ranked> rank(List<Evaluated> evaluated, int count, @Nullable CandidateEvaluator.Outcome reference,
                      Candidate base, Map<Long, SegmentInfo> segments) {
        List<Evaluated> sorted = evaluated.stream()
                .filter(e -> !e.outcome().blocked())
                .sorted(Comparator.comparing((Evaluated e) -> e.outcome().arrival())
                        .thenComparingDouble(e -> e.outcome().distanceMeters()))
                .toList();
        List<Evaluated> kept = new ArrayList<>();
        for (Evaluated candidate : sorted) {
            if (kept.size() == count) {
                break;
            }
            if (!tooSimilar(candidate, kept)) {
                kept.add(candidate);
            }
        }
        double shortest = kept.stream().mapToDouble(e -> e.outcome().distanceMeters()).min().orElse(0);
        Set<Long> baseIds = ids(base.segments());
        List<Ranked> ranked = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            Evaluated e = kept.get(i);
            Set<OptionLabel> labels = EnumSet.noneOf(OptionLabel.class);
            if (i == 0) {
                labels.add(OptionLabel.FASTEST);
            }
            if (e.outcome().distanceMeters() == shortest) {
                labels.add(OptionLabel.SHORTEST);
            }
            if (e.sharedRatio() != null && e.sharedRatio() >= properties.currentSharedRatio()) {
                labels.add(OptionLabel.CURRENT);
            }
            if (!e.outcome().conflicts().isEmpty()) {
                labels.add(OptionLabel.HAS_INCIDENT);
            }
            if (reference != null && !e.outcome().incidentIds().containsAll(reference.incidentIds())) {
                labels.add(OptionLabel.AVOIDS_INCIDENT);
            }
            if (reference != null && e.candidate().kind() == OptionKind.DEPARTURE_SHIFT) {
                labels.add(e.outcome().departure().isAfter(reference.departure())
                        ? OptionLabel.LATER_DEPARTURE : OptionLabel.EARLIER_DEPARTURE);
            }
            ranked.add(new Ranked(e, labels, via(e.candidate().segments(), baseIds, segments)));
        }
        return ranked;
    }

    private boolean tooSimilar(Evaluated candidate, List<Evaluated> better) {
        if (candidate.candidate().legs().size() < 2) {
            return false;
        }
        List<CandidateEvaluator.Step> steps = ViaPointExtractor.steps(candidate.candidate().segments());
        return better.stream().anyMatch(b -> {
            Double shared = CandidateEvaluator.sharedRatio(steps, ids(b.candidate().segments()));
            return shared != null && shared > properties.maxSharedRatio();
        });
    }

    /**
     * Road with the most distance in the part that differs from the fastest way ({@code ref}, else name), on the
     * fastest way itself the main road of the whole way.
     */
    @Nullable
    static String via(List<RoutedSegment> route, Set<Long> baseIds, Map<Long, SegmentInfo> segments) {
        List<RoutedSegment> differing = route.stream().filter(s -> !baseIds.contains(s.segmentId())).toList();
        Map<String, Double> byRoad = new HashMap<>();
        for (RoutedSegment segment : differing.isEmpty() ? route : differing) {
            SegmentInfo info = segments.get(segment.segmentId());
            String road = info == null ? null : info.ref() != null ? info.ref() : info.name();
            if (road != null) {
                byRoad.merge(road, segment.distanceMeters(), Double::sum);
            }
        }
        return byRoad.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    private static Set<Long> ids(List<RoutedSegment> segments) {
        return segments.stream().map(RoutedSegment::segmentId).collect(Collectors.toSet());
    }
}