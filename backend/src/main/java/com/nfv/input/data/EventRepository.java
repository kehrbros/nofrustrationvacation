package com.nfv.input.data;

import com.nfv.input.entities.Event;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {
    Optional<Event> findBySourceIdAndExternalId(long sourceId, String externalId);

    List<Event> findByProcessedAtIsNullOrderByReceivedAt();

    List<Event> findByIncidentId(long incidentId);
}
