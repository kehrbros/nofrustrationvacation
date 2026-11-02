package com.nfv.incidents.logic;

import com.nfv.incidents.data.IncidentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Reading incidents for other modules, e.g. to evaluate route suggestions. */
@Service
@RequiredArgsConstructor
public class ActiveIncidents {
    private final IncidentRepository incidentRepository;

    /** Incidents on any of the segments that have not ended before {@code from}. */
    @Transactional(readOnly = true)
    public List<ActiveIncident> onSegments(Collection<Long> segmentIds, OffsetDateTime from) {
        if (segmentIds.isEmpty()) {
            return List.of();
        }
        return incidentRepository.findActiveOnSegments(segmentIds, from).stream()
                .map(incident -> new ActiveIncident(incident.getId(), incident.impact(), incident.getExpectedDelay(),
                        incident.getValidFrom(), incident.getValidTo(), Set.copyOf(incident.getSegmentIds())))
                .toList();
    }
}