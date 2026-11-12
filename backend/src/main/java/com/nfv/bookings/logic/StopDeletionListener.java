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
 * payment would be left without its reservation. Reservations booked and paid in the app stay without their stop.
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
    private final BookingTodos todos;

    @EventListener
    public void onStopsDeleting(StopsDeleting event) {
        List<BookingReservation> reservations = reservationRepository.findByStopIdIn(event.stopIds());
        if (reservations.stream().anyMatch(reservation -> PAYMENT_RUNNING.contains(reservation.getStatus()))) {
            throw new StopInUseException("an alternative accommodation is being booked at the stop, try again later");
        }
        List<Booking> bookings = bookingRepository.findByStopIdInOrderByStartsAtAscIdAsc(event.stopIds());
        if (!event.withTrip() && !bookings.isEmpty()) {
            // planned again by hand: what is not needed any more goes on the to-do list of the trip (the copy stays,
            // the database detaches it from the booking)
            todos.stopsRemoved(event.tripId(), bookings);
        }
        // booked and paid (or a refund still due): the reservation stays without its stop, so it can still be
        // cancelled with a refund from the to-do list (and the background job finishes a refund that is due)
        reservations.stream().filter(BookingReservation::holdsMoney).forEach(BookingReservation::detachFromStop);
        reservationRepository.deleteAll(reservations.stream().filter(reservation -> !reservation.holdsMoney()).toList());
        if (!bookings.isEmpty()) {
            accommodationRepository.deleteAll(
                    accommodationRepository.findByBookingIdIn(bookings.stream().map(Booking::getId).toList()));
            bookingRepository.deleteAll(bookings);
        }
    }
}
