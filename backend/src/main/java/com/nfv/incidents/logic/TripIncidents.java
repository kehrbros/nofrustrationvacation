package com.nfv.incidents.logic;

import com.nfv.incidents.config.AssessmentProperties;
import com.nfv.incidents.data.ConsequenceRepository;
import com.nfv.incidents.entities.Category;
import com.nfv.incidents.entities.Consequence;
import com.nfv.incidents.entities.MatchKind;
import com.nfv.trips.logic.RouteSchedule;
import com.nfv.trips.logic.RouteSchedules;
import com.nfv.trips.logic.TripLogic;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Incidents as the travellers see them: per trip, via the consequences on its routes. Checks trip ownership. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripIncidents {
    /** Categories counted in the trip list, {@code NO_IMPACT} is shown but not counted. */
    static final Set<Category> COUNTED = EnumSet.of(Category.TOO_LATE, Category.DELAYED);

    private final TripLogic tripLogic;
    private final RouteSchedules routeSchedules;
    private final ConsequenceRepository consequenceRepository;
    private final AssessmentProperties rules;
    private final Clock clock;

    /**
     * Consequences on the routes of the trip whose incident has not ended yet, with the incident loaded, in the order
     * of {@link #RANKING}.
     */
    public List<Consequence> active(long appUserId, long tripId) {
        List<Long> routeIds = tripLogic.routes(appUserId, tripId).stream().map(r -> r.getId()).toList();
        return routeIds.isEmpty() ? List.of() : current(routeIds).stream().sorted(RANKING).toList();
    }

    /** Consequences whose incident has not ended and whose place the route has not passed yet. */
    private List<Consequence> current(Collection<Long> routeIds) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return consequenceRepository.findActiveOnRoutes(routeIds, now).stream()
                .filter(consequence -> !consequence.isOver(now, rules.maxWait())).toList();
    }

    /** Situation of each planned route of the trip, in the order of the stops. */
    public List<RouteIncidentSummary> routeSummaries(long appUserId, long tripId) {
        Map<Long, List<Consequence>> byRoute = active(appUserId, tripId).stream()
                .collect(Collectors.groupingBy(Consequence::getRouteId));
        return routeSchedules.ofTrip(appUserId, tripId).stream()
                .filter(schedule -> schedule.plannedArrival() != null || byRoute.containsKey(schedule.routeId()))
                .map(schedule -> summary(schedule, byRoute.getOrDefault(schedule.routeId(), List.of())))
                .toList();
    }

    /** Trip id → number of active consequences that are {@code TOO_LATE} or {@code DELAYED}, only trips with any. */
    public Map<Long, Integer> activeCountsByTrip(long appUserId) {
        Map<Long, Long> tripIdsByRouteId = tripLogic.tripIdsByRouteId(appUserId);
        Map<Long, Integer> counts = new TreeMap<>();
        if (!tripIdsByRouteId.isEmpty()) {
            current(tripIdsByRouteId.keySet()).stream()
                    .filter(consequence -> COUNTED.contains(consequence.getCategory()))
                    .forEach(consequence -> counts.merge(tripIdsByRouteId.get(consequence.getRouteId()), 1,
                            Integer::sum));
        }
        return counts;
    }

    static RouteIncidentSummary summary(RouteSchedule schedule, List<Consequence> consequences) {
        Category worst = consequences.stream().map(Consequence::getCategory).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        boolean unpassable = consequences.stream()
                .anyMatch(c -> c.isUnpassable() && c.getMatch() != MatchKind.MARGINAL);
        OffsetDateTime expected = unpassable || schedule.plannedArrival() == null ? null
                : consequences.stream().map(Consequence::getExpectedArrival).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(schedule.plannedArrival());
        Duration slack = expected == null || schedule.deadline() == null ? null
                : Duration.between(expected, schedule.deadline());
        boolean plannedTooLate = schedule.plannedArrival() != null && schedule.deadline() != null
                && schedule.plannedArrival().isAfter(schedule.deadline());
        return new RouteIncidentSummary(schedule.routeId(), worst, schedule.plannedArrival(), expected,
                schedule.deadline(), slack, unpassable, plannedTooLate);
    }

    /**
     * Ranking within a trip, see Docs/consequence-assessment.md#rangfolge: {@code TOO_LATE} by lateness at the
     * deadline (not passable first), {@code DELAYED} by own delay, both descending, {@code NO_IMPACT} by passage;
     * ties by earlier passage. Not yet assessed last.
     */
    static final Comparator<Consequence> RANKING = Comparator
            .comparing((Consequence c) -> c.getCategory() == null ? Category.values().length
                    : c.getCategory().ordinal())
            .thenComparing(TripIncidents::severity, Comparator.reverseOrder())
            .thenComparing(c -> c.getPassageAt(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Consequence::getId);

    /** Larger is worse within the category; 0 for {@code NO_IMPACT}, ordered by passage only. */
    private static long severity(Consequence consequence) {
        if (consequence.getCategory() == Category.TOO_LATE) {
            return lateness(consequence.getExpectedArrival(), consequence.getDeadline(), consequence.isUnpassable());
        }
        if (consequence.getCategory() == Category.DELAYED) {
            return consequence.getAssessedDelay() == null ? Long.MAX_VALUE
                    : consequence.getAssessedDelay().toSeconds();
        }
        return 0;
    }

    private static long lateness(@Nullable OffsetDateTime arrival, @Nullable OffsetDateTime deadline,
                                 boolean unpassable) {
        if (unpassable || arrival == null || deadline == null) {
            return Long.MAX_VALUE;
        }
        return Duration.between(deadline, arrival).toSeconds();
    }
}
