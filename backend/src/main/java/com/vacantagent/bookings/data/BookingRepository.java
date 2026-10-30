package com.vacantagent.bookings.data;

import com.vacantagent.bookings.entities.Booking;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    List<Booking> findByStopId(long stopId);

    List<Booking> findByStopIdIn(Collection<Long> stopIds);
}
