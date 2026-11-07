package com.nfv.recommendations.logic;

import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.logic.ActiveIncident;
import com.nfv.network.logic.RouteOptions;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Passage times, incidents and delays along a way, for candidates and the planned route alike, see
 * Docs/recommendations.md#bewertung-aussortieren-ranking. Pure functions.
 */
final class CandidateEvaluator {
    private CandidateEvaluator() {
    }

    /** One driven segment. */
    record Step(long segmentId, double distanceMeters, Duration duration) {
    }

    /**
     * When one step is driven.
     *
     * @param start        moved by the delays before this step
     * @param end          without the delay of this step, which only moves the following ones
     * @param metersBefore distance driven before this step
     * @param blocked      passes a closure here
     */
    record Passage(OffsetDateTime start, OffsetDateTime end, double metersBefore, double meters, long segmentId,
                   boolean blocked) {
    }

    /**
     * @param delay     sum of the delays on the way, already in {@code arrival}
     * @param blocked   passes a closure (blocked segment or incident with impact {@code BLOCKED}) during its validity
     * @param conflicts incidents passed during their validity, each once
     * @param passages  one per step, in order
     */
    record Outcome(OffsetDateTime departure, OffsetDateTime arrival, double distanceMeters, Duration duration,
                   Duration delay, boolean blocked, List<SuggestedOption.Conflict> conflicts,
                   List<Passage> passages) {
        Set<Long> incidentIds() {
            Set<Long> ids = new HashSet<>();
            conflicts.forEach(conflict -> ids.add(conflict.incidentId()));
            return ids;
        }
    }

    /**
     * Passage of every segment: departure plus the durations and delays before it. A delay (incident or scenario)
     * counts once and shifts all following segments, so later incidents are checked at the delayed time.
     */
    static Outcome evaluate(List<Step> steps, OffsetDateTime departure, RouteOptions constraints,
                            Collection<ActiveIncident> incidents) {
        Map<Long, List<ActiveIncident>> bySegment = new HashMap<>();
        for (ActiveIncident incident : incidents) {
            incident.segmentIds().forEach(id -> bySegment.computeIfAbsent(id, k -> new ArrayList<>()).add(incident));
        }
        OffsetDateTime passage = departure;
        Duration delay = Duration.ZERO;
        Duration duration = Duration.ZERO;
        double distance = 0;
        boolean blocked = false;
        Set<Long> delayedSeen = new HashSet<>();
        Map<Long, SuggestedOption.Conflict> conflicts = new LinkedHashMap<>();
        List<Passage> passages = new ArrayList<>(steps.size());
        for (Step step : steps) {
            OffsetDateTime end = passage.plus(step.duration());
            Duration extra = Duration.ZERO;
            boolean closed = constraints.blockedSegmentIds().contains(step.segmentId());
            Duration scenarioDelay = constraints.delayedSegments().get(step.segmentId());
            if (scenarioDelay != null && delayedSeen.add(step.segmentId())) {
                extra = extra.plus(scenarioDelay);
            }
            for (ActiveIncident incident : bySegment.getOrDefault(step.segmentId(), List.of())) {
                if (!incident.affectsCars() || conflicts.containsKey(incident.id())
                        || !incident.overlaps(passage, end)) {
                    continue;
                }
                conflicts.put(incident.id(), new SuggestedOption.Conflict(incident.id(), incident.impact(),
                        incident.delay()));
                if (incident.impact() == Impact.BLOCKED) {
                    closed = true;
                } else if (incident.delay() != null) {
                    extra = extra.plus(incident.delay());
                }
            }
            passages.add(new Passage(passage, end, distance, step.distanceMeters(), step.segmentId(), closed));
            blocked = blocked || closed;
            delay = delay.plus(extra);
            duration = duration.plus(step.duration());
            distance += step.distanceMeters();
            passage = end.plus(extra);
        }
        return new Outcome(departure, passage, distance, duration, delay, blocked, List.copyOf(conflicts.values()),
                List.copyOf(passages));
    }

    /** Part of the distance on segments of the other way, {@code null} without the other way. */
    @Nullable
    static Double sharedRatio(List<Step> steps, Set<Long> otherSegmentIds) {
        if (otherSegmentIds.isEmpty()) {
            return null;
        }
        double total = 0;
        double shared = 0;
        for (Step step : steps) {
            total += step.distanceMeters();
            if (otherSegmentIds.contains(step.segmentId())) {
                shared += step.distanceMeters();
            }
        }
        return total == 0 ? 1.0 : shared / total;
    }
}