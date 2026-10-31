package com.vacantagent.trips.data;

import com.vacantagent.trips.entities.Route;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RouteRepository extends JpaRepository<Route, Long> {
    List<Route> findByTripId(long tripId);

    Optional<Route> findByFromStopIdAndToStopId(long fromStopId, long toStopId);

    /** Route ids with their trip id, of all trips of the user: {@code [routeId, tripId]}. */
    @Query("SELECT r.id, r.trip.id FROM Route r WHERE r.trip.appUserId = :appUserId")
    List<Object[]> findRouteAndTripIdsOfUser(@Param("appUserId") long appUserId);
}
