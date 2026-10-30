package com.vacantagent.incidents.logic;

import com.vacantagent.incidents.data.IncidentRepository;
import com.vacantagent.incidents.entities.Incident;
import com.vacantagent.incidents.entities.IncidentType;
import jakarta.annotation.Nullable;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;

/** Creates and changes incidents and keeps their consequences up to date. */
@Service
@RequiredArgsConstructor
public class IncidentLogic {
    private final IncidentRepository incidentRepository;
    private final ConsequenceMatcher consequenceMatcher;

    @Transactional
    public Incident create(IncidentType type, @Nullable String description, OffsetDateTime validFrom,
                           @Nullable OffsetDateTime validTo, @Nullable Duration expectedDelay, Set<Long> segmentIds) {
        Incident incident = incidentRepository.saveAndFlush(
                new Incident(type, description, validFrom, validTo, expectedDelay, segmentIds));
        consequenceMatcher.matchIncident(incident);
        return incident;
    }

    @Transactional
    public Incident change(long incidentId, @Nullable String description, OffsetDateTime validFrom,
                           @Nullable OffsetDateTime validTo, @Nullable Duration expectedDelay, Set<Long> segmentIds) {
        Incident incident = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new EntityNotFoundException("incident " + incidentId));
        incident.change(description, validFrom, validTo, expectedDelay, segmentIds);
        incidentRepository.flush();
        consequenceMatcher.matchIncident(incident);
        return incident;
    }
}
