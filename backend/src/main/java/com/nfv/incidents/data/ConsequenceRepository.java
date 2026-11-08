package com.nfv.incidents.data;

import com.nfv.incidents.entities.Consequence;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ConsequenceRepository extends JpaRepository<Consequence, Long> {
    @EntityGraph(attributePaths = "incident")
    List<Consequence> findByIncidentId(long incidentId);

    @EntityGraph(attributePaths = "incident")
    List<Consequence> findByRouteId(long routeId);

    /** All consequences on the routes, also resolved ones, with their incident. */
    @Query("""
            SELECT c FROM Consequence c JOIN FETCH c.incident i
            WHERE c.routeId IN :routeIds
            ORDER BY i.validFrom, c.id""")
    List<Consequence> findOnRoutes(@Param("routeIds") Collection<Long> routeIds);

    /** Not yet resolved consequences whose incident has ended at {@code now}, see {@code Incident#hasEnded}. */
    @Query("""
            SELECT c FROM Consequence c JOIN FETCH c.incident i
            WHERE c.resolvedAt IS NULL AND i.validTo IS NOT NULL AND (i.validTo <= :now OR i.validTo <= i.validFrom)""")
    List<Consequence> findUnresolvedEnded(@Param("now") OffsetDateTime now);

    @EntityGraph(attributePaths = "incident")
    Optional<Consequence> findByRouteIdAndIncidentId(long routeId, long incidentId);
}
