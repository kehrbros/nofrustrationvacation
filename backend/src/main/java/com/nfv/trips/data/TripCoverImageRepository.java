package com.nfv.trips.data;

import com.nfv.trips.entities.TripCoverImage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TripCoverImageRepository extends JpaRepository<TripCoverImage, Long> {
    Optional<TripCoverImage> findByTripId(long tripId);

    /** Photos without thumbnail, for the backfill on startup (also those that cannot be decoded). */
    @Query("SELECT c.id FROM TripCoverImage c WHERE c.thumbnailContent IS NULL ORDER BY c.id")
    List<Long> findIdsWithoutThumbnail();
}
