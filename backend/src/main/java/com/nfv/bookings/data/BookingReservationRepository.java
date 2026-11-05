package com.nfv.bookings.data;

import com.nfv.bookings.entities.BookingReservation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingReservationRepository extends JpaRepository<BookingReservation, Long> {
}
