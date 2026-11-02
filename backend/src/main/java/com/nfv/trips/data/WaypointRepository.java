package com.nfv.trips.data;

import com.nfv.trips.entities.Waypoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WaypointRepository extends JpaRepository<Waypoint, Long> {
    List<Waypoint> findByRouteIdOrderByPosition(long routeId);
}