package com.vacantagent.trips.data;

import com.vacantagent.trips.entities.Route;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RouteRepository extends JpaRepository<Route, Long> {
    List<Route> findByTripId(long tripId);

    Optional<Route> findByFromStopIdAndToStopId(long fromStopId, long toStopId);
}
