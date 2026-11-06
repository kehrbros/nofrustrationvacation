package com.nfv.incidents.logic;

import com.nfv.incidents.config.AssessmentProperties;
import com.nfv.incidents.data.ConsequenceRepository;
import com.nfv.incidents.data.IncidentRepository;
import com.nfv.incidents.entities.Consequence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Reading incidents for other modules, e.g. to evaluate route suggestions. */
@Service
@RequiredArgsConstructor
public class ActiveIncidents {
    private final IncidentRepository incidentRepository;
    private final ConsequenceRepository consequenceRepository;
    private final AssessmentProperties rules;
    private final Clock clock;

    /** Incidents on any of the segments that have not ended before {@code from}. */
    @Transactional(readOnly = true)
    public List<ActiveIncident> onSegments(Collection<Long> segmentIds, OffsetDateTime from) {
        if (segmentIds.isEmpty()) {
            return List.of();
        }
        return incidentRepository.findActiveOnSegments(segmentIds, from).stream()
                .map(incident -> new ActiveIncident(incident.getId(), incident.impact(), incident.getExpectedDelay(),
                        incident.getAssumedDelay(), incident.getValidFrom(), incident.getValidTo(),
                        Set.copyOf(incident.getSegmentIds()), incident.timeWindows(), incident.getVehicleScope()))
                .toList();
    }

    /** The consequence, without checking who owns its route. */
    @Transactional(readOnly = true)
    public Optional<RouteConsequence> consequence(long consequenceId) {
        return consequenceRepository.findById(consequenceId).map(this::view);
    }

    /** All consequences of the route, also those {@link RouteConsequence#counts() that do not count}. */
    @Transactional(readOnly = true)
    public List<RouteConsequence> consequencesOnRoute(long routeId) {
        return consequenceRepository.findByRouteId(routeId).stream().map(this::view).toList();
    }

    private RouteConsequence view(Consequence consequence) {
        return new RouteConsequence(consequence.getId(), consequence.getRouteId(),
                consequence.getIncident().getId(), consequence.getIncident().getDescription(), consequence.impact(),
                consequence.getCategory(), consequence.getMatch(), consequence.getAssessedDelay(),
                consequence.isDelayAssumed(), Set.copyOf(consequence.getIncident().getSegmentIds()),
                consequence.getWindowStart() != null ? consequence.getWindowStart()
                        : consequence.getIncident().getValidFrom(),
                consequence.getWindowEnd() != null ? consequence.getWindowEnd()
                        : consequence.getIncident().getValidTo(),
                consequence.getPassageAt(), consequence.getExpectedArrival(),
                consequence.isOver(OffsetDateTime.now(clock), rules.maxWait()));
    }
}