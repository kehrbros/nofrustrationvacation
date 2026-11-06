package com.nfv.bookings.data;

import com.nfv.bookings.entities.BookingReservation;
import com.nfv.bookings.entities.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BookingReservationRepository extends JpaRepository<BookingReservation, Long> {
    List<BookingReservation> findByStopIdIn(Collection<Long> stopIds);

    Optional<BookingReservation> findByBookingId(long bookingId);

    List<BookingReservation> findByStatusIn(Collection<ReservationStatus> statuses);
}
