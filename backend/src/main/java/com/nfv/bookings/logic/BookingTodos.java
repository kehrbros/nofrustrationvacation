package com.nfv.bookings.logic;

import com.nfv.bookings.data.AccommodationDetailsRepository;
import com.nfv.bookings.data.BookingRepository;
import com.nfv.bookings.data.BookingReservationRepository;
import com.nfv.bookings.data.BookingTodoRepository;
import com.nfv.bookings.entities.AccommodationDetails;
import com.nfv.bookings.entities.Booking;
import com.nfv.bookings.entities.BookingStatus;
import com.nfv.bookings.entities.BookingTodo;
import com.nfv.bookings.entities.BookingType;
import com.nfv.bookings.entities.ReservationStatus;
import com.nfv.bookings.entities.TodoKind;
import com.nfv.bookings.entities.TodoReason;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.logic.TripLogic;
import com.nfv.trips.logic.TripNotFoundException;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The to-do list of a trip: bookings the user has to cancel or tell about a later arrival after the trip changed, see
 * Docs/domain.md#bookings. Nothing is cancelled automatically, cancelling moves money or is up to the provider.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookingTodos {
    private final BookingTodoRepository todoRepository;
    private final BookingRepository bookingRepository;
    private final AccommodationDetailsRepository accommodationRepository;
    private final BookingReservationRepository reservationRepository;
    private final TripLogic tripLogic;
    private final Clock clock;

    /** All to-dos of the trip, the newest first, also those done. */
    public List<BookingTodo> list(long appUserId, long tripId) {
        tripLogic.get(appUserId, tripId);
        return todoRepository.findByTripIdOrderByCreatedAtDescIdDesc(tripId);
    }

    /**
     * Done: for {@code CANCEL} the booking is marked cancelled in the app as well (the provider is not contacted).
     *
     * @throws BookingNotFoundException unknown to-do or one of another user
     */
    @Transactional
    public BookingTodo done(long appUserId, long todoId) {
        BookingTodo todo = owned(appUserId, todoId);
        todo.done(OffsetDateTime.now(clock));
        if (todo.getKind() == TodoKind.CANCEL && todo.getBookingId() != null) {
            bookingRepository.findById(todo.getBookingId()).ifPresent(Booking::cancel);
        }
        return todo;
    }

    /** Back on the list, e.g. ticked by mistake. A booking marked cancelled stays cancelled. */
    @Transactional
    public BookingTodo reopen(long appUserId, long todoId) {
        BookingTodo todo = owned(appUserId, todoId);
        todo.reopen();
        return todo;
    }

    /**
     * The booking is not needed any more: put it on the list to cancel, unless it is cancelled already or on the list.
     * Does not throw, it runs in the transaction of the change that made the booking obsolete.
     *
     * @return the new to-do, {@code null} if none was needed or the booking is gone (or of another user)
     */
    @Transactional
    public @Nullable BookingTodo obsolete(long appUserId, long bookingId, TodoReason reason) {
        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        Stop stop = booking == null ? null : tripLogic.stop(appUserId, booking.getStopId()).orElse(null);
        return stop == null ? null : add(stop.getTrip().getId(), booking, TodoKind.CANCEL, reason, null);

    }

    /**
     * The arrival at the stop moves to {@code arrivalOn} (local at the stop): every confirmed accommodation there
     * that starts earlier goes on the list, the user tells it about the late arrival or cancels the first night.
     *
     * @return the new to-dos
     */
    @Transactional
    public List<BookingTodo> lateArrival(long appUserId, long stopId, LocalDate arrivalOn) {
        Stop stop = tripLogic.stop(appUserId, stopId).orElseThrow(() -> BookingNotFoundException.stop(stopId));
        List<BookingTodo> added = new ArrayList<>();
        for (Booking booking : bookingRepository.findByStopIdInOrderByStartsAtAscIdAsc(List.of(stopId))) {
            if (booking.getType() == BookingType.ACCOMMODATION && booking.getStatus() == BookingStatus.CONFIRMED
                    && booking.getStartsAt() != null && booking.getStartsAt().toLocalDate().isBefore(arrivalOn)) {
                BookingTodo todo = add(stop.getTrip().getId(), booking, TodoKind.NOTIFY_LATE_ARRIVAL,
                        TodoReason.ARRIVAL_LATER, arrivalOn);
                if (todo != null) {
                    added.add(todo);
                }
            }
        }
        return added;
    }

    /**
     * Bookings at stops that are deleted now (not with their trip): confirmed ones that are not over go on the list,
     * in the transaction of the deletion. Called before the bookings are deleted, the database detaches the to-dos.
     */
    @Transactional
    void stopsRemoved(long tripId, List<Booking> bookings) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (Booking booking : bookings) {
            if (booking.getStatus() == BookingStatus.CONFIRMED
                    && (booking.getEndsAt() == null || booking.getEndsAt().isAfter(now))) {
                add(tripId, booking, TodoKind.CANCEL, TodoReason.STOP_REMOVED, null);
            }
        }
    }

    /** Marked cancelled in the app (by hand or by a cancellation in the app): its open cancellations are done. */
    @Transactional
    void cancelled(long bookingId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        todoRepository.findByBookingIdIn(List.of(bookingId)).stream()
                .filter(todo -> todo.getKind() == TodoKind.CANCEL)
                .forEach(todo -> todo.done(now));
    }

    private @Nullable BookingTodo add(long tripId, Booking booking, TodoKind kind, TodoReason reason,
                                      @Nullable LocalDate arrivalOn) {
        if (booking.getStatus() != BookingStatus.CONFIRMED
                || todoRepository.existsByBookingIdAndKindAndDoneAtIsNull(booking.getId(), kind)) {
            return null;
        }
        AccommodationDetails details = accommodationRepository.findByBookingId(booking.getId()).orElse(null);
        boolean bookedInApp = reservationRepository.findByBookingId(booking.getId())
                .filter(reservation -> reservation.getStatus() == ReservationStatus.BOOKED).isPresent();
        return todoRepository.save(new BookingTodo(tripId, booking, details == null ? null : details.getPhone(),
                details == null ? null : details.getEmail(), kind, reason, bookedInApp, arrivalOn,
                OffsetDateTime.now(clock)));
    }

    private BookingTodo owned(long appUserId, long todoId) {
        BookingTodo todo = todoRepository.findById(todoId).orElseThrow(() -> BookingNotFoundException.todo(todoId));
        try {
            tripLogic.get(appUserId, todo.getTripId());
        } catch (TripNotFoundException e) {
            throw BookingNotFoundException.todo(todoId);
        }
        return todo;
    }
}
