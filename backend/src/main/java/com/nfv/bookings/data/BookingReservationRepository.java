package com.nfv.bookings.data;

import com.nfv.bookings.entities.BookingReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BookingReservationRepository extends JpaRepository<BookingReservation, Long> {
    List<BookingReservation> findByStopIdIn(Collection<Long> stopIds);
}
