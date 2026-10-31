package com.vacantagent.trips.data;

import com.vacantagent.trips.entities.Waypoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WaypointRepository extends JpaRepository<Waypoint, Long> {
    List<Waypoint> findByRouteIdOrderByPosition(long routeId);
}