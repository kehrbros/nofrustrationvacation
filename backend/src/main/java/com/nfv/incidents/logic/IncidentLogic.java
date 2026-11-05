package com.nfv.incidents.logic;

import com.nfv.incidents.data.IncidentRepository;
import com.nfv.incidents.entities.Incident;
import com.nfv.incidents.entities.IncidentType;
import jakarta.annotation.Nullable;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;

/** Creates and changes incidents and keeps their consequences (and their assessment) up to date. */
@Service
@RequiredArgsConstructor
public class IncidentLogic {
    private final IncidentRepository incidentRepository;
    private final ConsequenceMatcher consequenceMatcher;

    @Transactional
    public Incident create(IncidentType type, @Nullable String description, OffsetDateTime validFrom,
                           @Nullable OffsetDateTime validTo, @Nullable Duration expectedDelay, Set<Long> segmentIds) {
        return create(IncidentDetails.of(type, description, validFrom, validTo, expectedDelay), segmentIds);
    }

    @Transactional
    public Incident create(IncidentDetails details, Set<Long> segmentIds) {
        Incident incident = incidentRepository.saveAndFlush(new Incident(details, segmentIds));
        consequenceMatcher.matchIncident(incident);
        return incident;
    }

    /** Changes everything but type, assumed delay, windows and vehicle scope. */
    @Transactional
    public Incident change(long incidentId, @Nullable String description, OffsetDateTime validFrom,
                           @Nullable OffsetDateTime validTo, @Nullable Duration expectedDelay, Set<Long> segmentIds) {
        IncidentDetails current = find(incidentId).details();
        return change(incidentId, new IncidentDetails(current.type(), description, validFrom, validTo, expectedDelay,
                current.assumedDelay(), current.windows(), current.vehicleScope(), current.timesUnconfirmed()),
                segmentIds);
    }

    /**
     * Changes everything including the type: same id, Envers keeps the old values, consequences are updated (e.g.
     * {@code DELAYED} → {@code BLOCKED}) and keep their ids.
     */
    @Transactional
    public Incident change(long incidentId, IncidentDetails details, Set<Long> segmentIds) {
        Incident incident = find(incidentId);
        incident.change(details, segmentIds);
        incidentRepository.flush();
        consequenceMatcher.matchIncident(incident);
        return incident;
    }

    private Incident find(long incidentId) {
        return incidentRepository.findById(incidentId)
                .orElseThrow(() -> new EntityNotFoundException("incident " + incidentId));
    }
}
