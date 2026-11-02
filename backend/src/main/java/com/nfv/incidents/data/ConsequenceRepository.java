package com.nfv.incidents.data;

import com.nfv.incidents.entities.Consequence;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

public interface ConsequenceRepository extends JpaRepository<Consequence, Long> {
    List<Consequence> findByIncidentId(long incidentId);

    @EntityGraph(attributePaths = "incident")
    List<Consequence> findByRouteId(long routeId);

    /** Consequences on the routes whose incident has not ended before {@code now}. */
    @Query("""
            SELECT c FROM Consequence c JOIN FETCH c.incident i
            WHERE c.routeId IN :routeIds AND (i.validTo IS NULL OR i.validTo >= :now)
            ORDER BY i.validFrom, c.id""")
    List<Consequence> findActiveOnRoutes(@Param("routeIds") Collection<Long> routeIds, @Param("now") OffsetDateTime now);
}
