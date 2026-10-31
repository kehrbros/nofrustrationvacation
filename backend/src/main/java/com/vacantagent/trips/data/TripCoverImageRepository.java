package com.vacantagent.trips.data;

import com.vacantagent.trips.entities.TripCoverImage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TripCoverImageRepository extends JpaRepository<TripCoverImage, Long> {
    Optional<TripCoverImage> findByTripId(long tripId);
}
