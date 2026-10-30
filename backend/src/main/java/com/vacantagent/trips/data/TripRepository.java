package com.vacantagent.trips.data;

import com.vacantagent.trips.entities.Trip;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TripRepository extends JpaRepository<Trip, Long> {
    List<Trip> findByAppUserIdOrderByCreatedAtDesc(long appUserId);
}
