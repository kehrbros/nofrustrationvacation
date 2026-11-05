package com.nfv.incidents.logic;

import com.nfv.incidents.config.AssessmentProperties;
import com.nfv.incidents.data.ConsequenceRepository;
import com.nfv.incidents.entities.Consequence;
import com.nfv.incidents.entities.Incident;
import com.nfv.trips.data.RouteSectionRepository;
import com.nfv.trips.entities.RouteSection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps {@link Consequence}s in line with incidents and routes, see Docs/domain.md and
 * Docs/consequence-assessment.md:
 * <ul>
 *     <li>incident new or changed: its segments → routes passing them around its validity (index segment → route
 *     section), plus the routes it already has consequences on → each route assessed as a whole</li>
 *     <li>route new or changed, or the arrival of its target stop changed: the route assessed as a whole</li>
 * </ul>
 * The assessment ({@link ConsequenceAssessment}) decides which incidents hit the route, including hits only by
 * earlier delays ({@code BY_SHIFT}) or within the tolerance ({@code MARGINAL}). Consequences keep their ids (for
 * notifications later) and are removed when they no longer apply.
 */
@Component
@RequiredArgsConstructor
public class ConsequenceMatcher {
    private final ConsequenceRepository consequenceRepository;
    private final RouteSectionRepository routeSectionRepository;
    private final ConsequenceAssessment assessment;
    private final AssessmentProperties rules;

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Consequence> matchIncident(Incident incident) {
        // Envelope widened: a passage up to max-wait before the start can be moved into it by earlier delays, the
        // tolerance reaches up to tolerance-max on both sides
        OffsetDateTime from = incident.getValidFrom().minus(rules.maxWait()).minus(rules.toleranceMax());
        List<RouteSection> passing = incident.getValidTo() == null
                ? routeSectionRepository.findPassingAfter(incident.getSegmentIds(), from)
                : routeSectionRepository.findPassing(incident.getSegmentIds(), from,
                incident.getValidTo().plus(rules.toleranceMax()));
        Set<Long> routeIds = new LinkedHashSet<>();
        passing.forEach(section -> routeIds.add(section.getRoute().getId()));
        consequenceRepository.findByIncidentId(incident.getId()).forEach(c -> routeIds.add(c.getRouteId()));
        return assessRoutes(routeIds).stream()
                .filter(c -> c.getIncident().getId() == incident.getId())
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<Consequence> matchRoute(long routeId) {
        return assessment.assessRoute(routeId);
    }

    /** Assesses the routes again, e.g. after the deadline of their target stop changed. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Consequence> assessRoutes(Collection<Long> routeIds) {
        List<Consequence> consequences = new ArrayList<>();
        routeIds.forEach(routeId -> consequences.addAll(assessment.assessRoute(routeId)));
        return consequences;
    }
}
