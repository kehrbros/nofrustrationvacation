package com.vacantagent.trips.data;

import com.vacantagent.trips.entities.Stop;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface StopRepository extends JpaRepository<Stop, Long> {
    List<Stop> findByTripIdOrderByPosition(long tripId);

    List<Stop> findByTripIdInOrderByTripIdAscPositionAsc(Collection<Long> tripIds);
}
