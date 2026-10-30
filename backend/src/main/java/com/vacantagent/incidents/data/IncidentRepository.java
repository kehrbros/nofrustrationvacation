package com.vacantagent.incidents.data;

import com.vacantagent.incidents.entities.Incident;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface IncidentRepository extends JpaRepository<Incident, Long> {
    /** Incidents on any of the segments that have not ended before {@code now}. */
    @Query("""
            SELECT DISTINCT i FROM Incident i JOIN i.segmentIds s
            WHERE s IN :segmentIds AND (i.validTo IS NULL OR i.validTo >= :now)""")
    List<Incident> findActiveOnSegments(@Param("segmentIds") Collection<Long> segmentIds,
                                        @Param("now") OffsetDateTime now);
}
