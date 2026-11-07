package com.nfv.bookings.logic;

import com.nfv.bookings.data.AccommodationDetailsRepository;
import com.nfv.bookings.data.BookingRepository;
import com.nfv.bookings.data.BookingReservationRepository;
import com.nfv.bookings.entities.Booking;
import com.nfv.bookings.entities.BookingReservation;
import com.nfv.bookings.entities.ReservationStatus;
import com.nfv.trips.logic.StopInUseException;
import com.nfv.trips.logic.StopsDeleting;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Deletes the bookings and reservations of alternatives at stops that are deleted, in the transaction of the
 * deletion. Only while money is reserved at PayPal and the booking is not finished yet, the deletion is refused: the
 * payment would be left without its reservation.
 */
@Component
@RequiredArgsConstructor
class StopDeletionListener {
    private static final Set<ReservationStatus> PAYMENT_RUNNING =
            EnumSet.of(ReservationStatus.AUTHORIZED, ReservationStatus.PROVIDER_BOOKED,
                    ReservationStatus.NEEDS_ATTENTION);

    private final BookingRepository bookingRepository;
    private final AccommodationDetailsRepository accommodationRepository;
    private final BookingReservationRepository reservationRepository;

    @EventListener
    public void onStopsDeleting(StopsDeleting event) {
        List<BookingReservation> reservations = reservationRepository.findByStopIdIn(event.stopIds());
        if (reservations.stream().anyMatch(reservation -> PAYMENT_RUNNING.contains(reservation.getStatus()))) {
            throw new StopInUseException("an alternative accommodation is being booked at the stop, try again later");
        }
        reservationRepository.deleteAll(reservations);
        List<Booking> bookings = bookingRepository.findByStopIdInOrderByStartsAtAscIdAsc(event.stopIds());
        if (!bookings.isEmpty()) {
            accommodationRepository.deleteAll(
                    accommodationRepository.findByBookingIdIn(bookings.stream().map(Booking::getId).toList()));
            bookingRepository.deleteAll(bookings);
        }
    }
}
