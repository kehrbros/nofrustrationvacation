package com.vacantagent.incidents.logic;

import com.vacantagent.incidents.data.ConsequenceRepository;
import com.vacantagent.incidents.data.IncidentRepository;
import com.vacantagent.incidents.entities.Consequence;
import com.vacantagent.incidents.entities.Impact;
import com.vacantagent.incidents.entities.Incident;
import com.vacantagent.incidents.entities.IncidentType;
import com.vacantagent.trips.data.RouteSectionRepository;
import com.vacantagent.trips.entities.RouteSection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Keeps {@link Consequence}s in line with incidents and routes, see Docs/domain.md:
 * <ul>
 *     <li>incident new or changed: its segments → route sections on them (index segment → route section) → a
 *     consequence for every route that passes during the validity of the incident</li>
 *     <li>route new or changed: its segments → active incidents on them → the same overlap check</li>
 * </ul>
 * Existing consequences are updated, so their ids stay stable (for notifications later), and removed when they no
 * longer apply.
 */
@Component
@RequiredArgsConstructor
public class ConsequenceMatcher {
    private final IncidentRepository incidentRepository;
    private final ConsequenceRepository consequenceRepository;
    private final RouteSectionRepository routeSectionRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Consequence> matchIncident(Incident incident) {
        List<RouteSection> passing = incident.getValidTo() == null
                ? routeSectionRepository.findPassingAfter(incident.getSegmentIds(), incident.getValidFrom())
                : routeSectionRepository.findPassing(incident.getSegmentIds(), incident.getValidFrom(), incident.getValidTo());
        Map<Long, RouteSection> firstByRoute = firstByRoute(passing);

        Map<Long, Consequence> existing = consequenceRepository.findByIncidentId(incident.getId()).stream()
                .collect(Collectors.toMap(Consequence::getRouteId, Function.identity()));
        return apply(existing, firstByRoute.entrySet().stream()
                .map(e -> new Match(incident, e.getKey(), e.getValue()))
                .toList(), Match::routeId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Consequence> matchRoute(long routeId) {
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        List<Match> matches = new ArrayList<>();
        if (!sections.isEmpty()) {
            List<Long> segmentIds = sections.stream().map(RouteSection::getSegmentId).distinct().toList();
            for (Incident incident : incidentRepository.findActiveOnSegments(segmentIds, OffsetDateTime.now(clock))) {
                sections.stream()
                        .filter(s -> incident.getSegmentIds().contains(s.getSegmentId()))
                        .filter(s -> incident.overlaps(s.getExpectedPassageStart(), s.getExpectedPassageEnd()))
                        .findFirst()
                        .ifPresent(s -> matches.add(new Match(incident, routeId, s)));
            }
        }
        Map<Long, Consequence> existing = consequenceRepository.findByRouteId(routeId).stream()
                .collect(Collectors.toMap(c -> c.getIncident().getId(), Function.identity()));
        return apply(existing, matches, m -> m.incident().getId());
    }

    /**
     * Updates or creates a consequence per match, removes the existing ones without a match.
     *
     * @param existing existing consequences by {@code key}, the route id or the incident id
     */
    private List<Consequence> apply(Map<Long, Consequence> existing, List<Match> matches, Function<Match, Long> key) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Map<Long, Consequence> remaining = new HashMap<>(existing);
        List<Consequence> result = new ArrayList<>();
        for (Match match : matches) {
            Incident incident = match.incident();
            Consequence consequence = remaining.remove(key.apply(match));
            if (consequence == null) {
                consequence = new Consequence(incident, match.routeId(), match.section().getId(), impactOf(incident),
                        incident.getExpectedDelay(), now);
            } else {
                consequence.update(match.section().getId(), impactOf(incident), incident.getExpectedDelay(), now);
            }
            result.add(consequence);
        }
        consequenceRepository.deleteAll(remaining.values());
        return consequenceRepository.saveAll(result);
    }

    private static Map<Long, RouteSection> firstByRoute(List<RouteSection> sections) {
        return sections.stream().collect(Collectors.toMap(s -> s.getRoute().getId(), Function.identity(),
                (a, b) -> a.getPosition() <= b.getPosition() ? a : b,
                LinkedHashMap::new));
    }

    private static Impact impactOf(Incident incident) {
        return incident.getType() == IncidentType.CLOSURE ? Impact.BLOCKED : Impact.DELAYED;
    }

    private record Match(Incident incident, long routeId, RouteSection section) {
    }
}
