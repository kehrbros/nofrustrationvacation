package com.nfv.trips.data;

import com.nfv.trips.entities.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TripRepository extends JpaRepository<Trip, Long> {
    List<Trip> findByAppUserIdOrderByStartAtAscIdAsc(long appUserId);

    Optional<Trip> findByIdAndAppUserId(long id, long appUserId);

    /** Ids of the trips (of the given ones) that have a title photo. */
    @Query("SELECT c.tripId FROM TripCoverImage c WHERE c.tripId IN :tripIds")
    List<Long> findTripIdsWithCoverImage(@Param("tripIds") Collection<Long> tripIds);
}
