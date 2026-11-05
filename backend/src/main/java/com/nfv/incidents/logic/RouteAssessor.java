package com.nfv.incidents.logic;

import com.nfv.incidents.config.AssessmentProperties;
import com.nfv.incidents.entities.Category;
import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.entities.MatchKind;
import com.nfv.incidents.entities.VehicleScope;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Assessment of one route as a pure function, see Docs/consequence-assessment.md#verzögerung-je-consequence:
 * sections in driving order, each incident on them once (first section it hits), delays accumulate as {@code shift}
 * and move the passage of all later sections.
 * <ul>
 *     <li>hit: the moved passage, widened by the tolerance, overlaps the incident (with time windows);
 *     {@code DIRECT} if the planned passage hits without tolerance, {@code BY_SHIFT} if only the moved one does,
 *     {@code MARGINAL} if only with tolerance</li>
 *     <li>delay: closure → wait until the end of the window hit or the validity (from the passage without
 *     tolerance, from the start of the window if the passage is before it), without end or longer than
 *     {@code max-wait} not passable; otherwise {@code expectedDelay}, else the assumption of the source (estimated),
 *     else 0; {@code HEAVY_ONLY} → 0</li>
 *     <li>category: below {@code no-impact-below} {@code NO_IMPACT}, otherwise {@code DELAYED}; {@code TOO_LATE}
 *     from the first consequence after which the expected arrival passes the deadline, and every later one with its
 *     own delay. {@code MARGINAL} is never {@code TOO_LATE} and does not add to {@code shift}. Without deadline never
 *     {@code TOO_LATE}. If the plan already arrives after the deadline, only a not passable incident is
 *     {@code TOO_LATE}.</li>
 *     <li>{@code timesUnconfirmed} (closure whose times could not be read yet): {@code DELAYED} with delay 0, adds
 *     nothing to {@code shift}, never {@code TOO_LATE}</li>
 * </ul>
 */
public final class RouteAssessor {
    private RouteAssessor() {
    }

    public record Section(long id, long segmentId, OffsetDateTime passageStart, OffsetDateTime passageEnd) {
    }

    /** What the assessment needs of an incident. */
    public record IncidentFacts(long id, Impact impact, @Nullable Duration expectedDelay,
                                @Nullable Duration assumedDelay, OffsetDateTime validFrom,
                                @Nullable OffsetDateTime validTo, List<TimeWindow> windows, Set<Long> segmentIds,
                                VehicleScope vehicleScope, boolean timesUnconfirmed) {
        boolean overlaps(OffsetDateTime start, OffsetDateTime end) {
            return IncidentOverlap.overlaps(validFrom, validTo, windows, start, end);
        }
    }

    /**
     * Assessment of one incident on the route.
     *
     * @param delay           own delay, {@code null}: not passable
     * @param passageAt       start of the passage of the section, moved by the delays before it
     * @param expectedArrival arrival at the target stop after this incident, {@code null} if not passable (now or
     *                        before) or the route has no planned arrival
     * @param window          window of the incident hit, {@code null} without windows
     */
    public record Assessment(long incidentId, long sectionId, MatchKind match, Category category,
                             @Nullable Duration delay, boolean delayAssumed, OffsetDateTime passageAt,
                             @Nullable OffsetDateTime expectedArrival, @Nullable TimeWindow window) {
    }

    /**
     * @param plannedArrival end of the last section, {@code null} if unknown
     * @param deadline       arrival at the target stop, {@code null}: no {@code TOO_LATE}
     */
    public static List<Assessment> assess(List<Section> sections, List<IncidentFacts> incidents,
                                          @Nullable OffsetDateTime plannedArrival, @Nullable OffsetDateTime deadline,
                                          AssessmentProperties rules) {
        List<Assessment> assessments = new ArrayList<>();
        if (sections.isEmpty()) {
            return assessments;
        }
        OffsetDateTime departure = sections.getFirst().passageStart();
        boolean plannedLate = plannedArrival != null && deadline != null && plannedArrival.isAfter(deadline);
        List<IncidentFacts> ordered = incidents.stream().sorted(Comparator.comparingLong(IncidentFacts::id)).toList();
        Set<Long> done = new HashSet<>();
        Duration shift = Duration.ZERO;
        boolean blocked = false;
        for (Section section : sections) {
            for (IncidentFacts incident : ordered) {
                if (done.contains(incident.id()) || !incident.segmentIds().contains(section.segmentId())) {
                    continue;
                }
                OffsetDateTime start = section.passageStart().plus(shift);
                OffsetDateTime end = section.passageEnd().plus(shift);
                Duration tolerance = rules.tolerance(Duration.between(departure, start));
                if (!incident.overlaps(start.minus(tolerance), end.plus(tolerance))) {
                    continue;
                }
                done.add(incident.id());
                MatchKind match = !incident.overlaps(start, end) ? MatchKind.MARGINAL
                        : incident.overlaps(section.passageStart(), section.passageEnd()) ? MatchKind.DIRECT
                        : MatchKind.BY_SHIFT;
                boolean marginal = match == MatchKind.MARGINAL;
                TimeWindow window = IncidentOverlap.firstWindow(incident.windows(), start.minus(tolerance),
                        end.plus(tolerance)).orElse(null);

                boolean unconfirmed = incident.timesUnconfirmed();
                Duration delay;
                boolean assumed = false;
                if (unconfirmed || incident.vehicleScope() == VehicleScope.HEAVY_ONLY) {
                    delay = Duration.ZERO;
                } else if (incident.impact() == Impact.BLOCKED) {
                    delay = waitFor(incident, window, start, rules);
                } else if (incident.expectedDelay() != null) {
                    delay = incident.expectedDelay();
                } else if (incident.assumedDelay() != null) {
                    delay = incident.assumedDelay();
                    assumed = true;
                } else {
                    delay = Duration.ZERO;
                }

                if (!marginal) {
                    if (delay == null) {
                        blocked = true;
                    } else {
                        shift = shift.plus(delay);
                    }
                }
                OffsetDateTime expectedArrival = blocked || plannedArrival == null ? null : plannedArrival.plus(shift);
                Category own = !unconfirmed && delay != null && delay.compareTo(rules.noImpactBelow()) < 0
                        ? Category.NO_IMPACT : Category.DELAYED;
                boolean late;
                if (deadline == null || marginal || unconfirmed || own == Category.NO_IMPACT) {
                    late = false;
                } else if (plannedLate) {
                    late = delay == null;
                } else {
                    late = blocked || expectedArrival != null && expectedArrival.isAfter(deadline);
                }
                assessments.add(new Assessment(incident.id(), section.id(), match, late ? Category.TOO_LATE : own,
                        delay, assumed, start, expectedArrival, window));
            }
        }
        return assessments;
    }

    /** Wait at a closure, {@code null} if not passable. */
    private static @Nullable Duration waitFor(IncidentFacts incident, @Nullable TimeWindow window,
                                              OffsetDateTime passage, AssessmentProperties rules) {
        OffsetDateTime reopens;
        OffsetDateTime closed;
        if (window != null) {
            reopens = window.endsAt();
            closed = window.startsAt();
        } else if (incident.validTo() != null) {
            reopens = incident.validTo();
            closed = incident.validFrom();
        } else {
            return null;
        }
        // passage before the closure (only hit with tolerance): assume arriving just too late
        OffsetDateTime from = passage.isBefore(closed) ? closed : passage;
        Duration wait = from.isAfter(reopens) ? Duration.ZERO : Duration.between(from, reopens);
        return wait.compareTo(rules.maxWait()) > 0 ? null : wait;
    }
}
