package com.vacantagent.incidents.data;

import com.vacantagent.incidents.entities.Consequence;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConsequenceRepository extends JpaRepository<Consequence, Long> {
    List<Consequence> findByIncidentId(long incidentId);

    @EntityGraph(attributePaths = "incident")
    List<Consequence> findByRouteId(long routeId);
}
