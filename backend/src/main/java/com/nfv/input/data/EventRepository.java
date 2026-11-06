package com.nfv.input.data;

import com.nfv.input.entities.Event;
import jakarta.annotation.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends JpaRepository<Event, Long> {
    Optional<Event> findBySourceIdAndExternalId(long sourceId, String externalId);

    List<Event> findByProcessedAtIsNullOrderByReceivedAt();

    List<Event> findByIncidentId(long incidentId);

    /** Traffic events not processed yet, oldest first, so versions of an event are processed in order. */
    @Query("SELECT e.id FROM TrafficEvent e WHERE e.processedAt IS NULL ORDER BY e.receivedAt, e.id")
    List<Long> findPendingTrafficEventIds();

    /** Latest event of the same event at the source that led to an incident. */
    Optional<Event> findFirstBySourceIdAndSubjectIdAndIncidentIdIsNotNullOrderByIdDesc(long sourceId,
                                                                                        String subjectId);

    /** Processed events still waiting for their enrichment, those with the fewest failed tries first, then oldest. */
    @Query("SELECT e.id FROM TrafficEvent e WHERE e.enrichmentPending = TRUE AND e.processedAt IS NOT NULL "
            + "ORDER BY e.enrichmentAttempts, e.receivedAt, e.id")
    List<Long> findEnrichmentPendingIds();

    /** State of an event, read from the database without the entity: another thread may just have processed it. */
    interface EventState {
        long getId();

        String getExternalId();

        @Nullable OffsetDateTime getProcessedAt();

        @Nullable Long getIncidentId();

        @Nullable String getProcessingNote();
    }

    /** State of the latest event of the same event at the source. */
    Optional<EventState> findFirstBySourceIdAndSubjectIdOrderByIdDesc(long sourceId, String subjectId);

    /** Incidents the source has led to. */
    @Query("SELECT DISTINCT e.incidentId FROM Event e WHERE e.source.id = :sourceId AND e.incidentId IS NOT NULL")
    List<Long> findIncidentIdsBySourceId(@Param("sourceId") long sourceId);
}
