package com.nfv.incidents.logic;

import com.nfv.incidents.config.AssessmentProperties;
import com.nfv.incidents.data.ConsequenceRepository;
import com.nfv.incidents.data.IncidentRepository;
import com.nfv.incidents.entities.Category;
import com.nfv.incidents.entities.Consequence;
import com.nfv.incidents.entities.Incident;
import com.nfv.trips.data.RouteSectionRepository;
import com.nfv.trips.entities.RouteSection;
import com.nfv.trips.logic.RouteSchedule;
import com.nfv.trips.logic.RouteSchedules;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Assesses a whole route against its target stop and stores the result as its {@link Consequence}s (created,
 * updated with stable ids, or removed), see Docs/consequence-assessment.md. Rules in {@link RouteAssessor}.
 * Runs in the transaction of the change that triggered it.
 */
@Component
@RequiredArgsConstructor
public class ConsequenceAssessment {
    private final IncidentRepository incidentRepository;
    private final ConsequenceRepository consequenceRepository;
    private final RouteSectionRepository routeSectionRepository;
    private final RouteSchedules routeSchedules;
    private final AssessmentProperties rules;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Consequence> assessRoute(long routeId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<RouteSchedule> schedule = routeSchedules.of(routeId);
        List<RouteSection> sections = schedule.isEmpty() ? List.of()
                : routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        List<Incident> incidents = sections.isEmpty() ? List.of() : incidentRepository.findActiveOnSegments(
                sections.stream().map(RouteSection::getSegmentId).collect(Collectors.toSet()), now);
        Map<Long, Incident> byId = incidents.stream().collect(Collectors.toMap(Incident::getId, Function.identity()));

        List<RouteAssessor.Assessment> assessments = RouteAssessor.assess(
                sections.stream().map(s -> new RouteAssessor.Section(s.getId(), s.getSegmentId(),
                        s.getExpectedPassageStart(), s.getExpectedPassageEnd())).toList(),
                incidents.stream().map(ConsequenceAssessment::facts).toList(),
                schedule.map(RouteSchedule::plannedArrival).orElse(null),
                schedule.map(RouteSchedule::deadline).orElse(null), rules);

        OffsetDateTime deadline = schedule.map(RouteSchedule::deadline).orElse(null);
        Map<Long, Consequence> remaining = new HashMap<>(consequenceRepository.findByRouteId(routeId).stream()
                .collect(Collectors.toMap(c -> c.getIncident().getId(), Function.identity())));
        List<Consequence> result = new ArrayList<>();
        boolean becameTooLate = false;
        for (RouteAssessor.Assessment assessment : assessments) {
            Consequence consequence = remaining.remove(assessment.incidentId());
            Category before = consequence == null ? null : consequence.getCategory();
            if (consequence == null) {
                consequence = new Consequence(byId.get(assessment.incidentId()), routeId, assessment, deadline, now);
            } else {
                consequence.assessed(assessment, deadline, now);
            }
            becameTooLate |= before != Category.TOO_LATE && consequence.getCategory() == Category.TOO_LATE
                    && !consequence.isOver(now, rules.maxWait());
            result.add(consequence);
        }
        consequenceRepository.deleteAll(remaining.values());
        List<Consequence> saved = consequenceRepository.saveAll(result);
        if (becameTooLate) {
            events.publishEvent(new RouteTooLate(routeId, saved.stream()
                    .filter(c -> c.getCategory() == Category.TOO_LATE)
                    .map(c -> c.getIncident().getId()).collect(Collectors.toSet()), deadline));
        }
        return saved;
    }

    static RouteAssessor.IncidentFacts facts(Incident incident) {
        return new RouteAssessor.IncidentFacts(incident.getId(), incident.impact(), incident.getExpectedDelay(),
                incident.getAssumedDelay(), incident.getValidFrom(), incident.getValidTo(), incident.timeWindows(),
                Set.copyOf(incident.getSegmentIds()), incident.getVehicleScope(), incident.isTimesUnconfirmed());
    }
}
