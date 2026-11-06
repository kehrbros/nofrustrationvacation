package com.nfv.bookings.logic;

import com.nfv.bookings.data.BookingReservationRepository;
import com.nfv.bookings.entities.BookingReservation;
import com.nfv.bookings.entities.BookingSource;
import com.nfv.bookings.entities.ReservationHotel;
import com.nfv.bookings.entities.ReservationStatus;
import jakarta.annotation.Nullable;
import com.nfv.trips.logic.TripLogic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;

/**
 * One transaction per step of a reservation, so every result of an external call (LiteAPI, PayPal) is committed
 * before the next call. The calls themselves run outside, in {@link AlternativeReservations}.
 */
@Component
@RequiredArgsConstructor
class ReservationSteps {
    private static final LocalTime DEFAULT_CHECK_IN = LocalTime.of(15, 0);
    private static final LocalTime DEFAULT_CHECK_OUT = LocalTime.of(11, 0);

    private final BookingReservationRepository repository;
    private final BookingLogic bookingLogic;
    private final TripLogic tripLogic;
    private final Clock clock;

    @Transactional(readOnly = true)
    BookingReservation owned(long appUserId, long reservationId) {
        return repository.findById(reservationId)
                .filter(reservation -> tripLogic.stop(appUserId, reservation.getStopId()).isPresent())
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }

    @Transactional(readOnly = true)
    BookingReservation find(long reservationId) {
        return repository.findById(reservationId).orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }

    @Transactional(readOnly = true)
    List<BookingReservation> ofStops(List<Long> stopIds) {
        return stopIds.isEmpty() ? List.of() : repository.findByStopIdIn(stopIds);
    }

    @Transactional(readOnly = true)
    java.util.Optional<BookingReservation> ofBooking(long appUserId, long bookingId) {
        return repository.findByBookingId(bookingId)
                .filter(reservation -> tripLogic.stop(appUserId, reservation.getStopId()).isPresent());
    }

    /** Reservations the background job may have to continue: payment running, or a refund still due. */
    @Transactional(readOnly = true)
    List<BookingReservation> unfinished() {
        return repository.findByStatusIn(EnumSet.of(ReservationStatus.PAYMENT_PENDING, ReservationStatus.AUTHORIZED,
                        ReservationStatus.PROVIDER_BOOKED, ReservationStatus.CANCELLED)).stream()
                .filter(reservation -> reservation.getStatus() != ReservationStatus.CANCELLED
                        || reservation.isRefundOpen())
                .toList();
    }

    @Transactional
    BookingReservation create(long appUserId, long stopId, long replacesBookingId, BookingReservation.Offer offer,
                              ReservationHotel hotel, BookingReservation.Holder holder) {
        return repository.save(new BookingReservation(appUserId, stopId, replacesBookingId, offer, hotel, holder,
                OffsetDateTime.now(clock)));
    }

    @Transactional
    BookingReservation attempted(long reservationId) {
        BookingReservation reservation = repository.findById(reservationId).orElseThrow();
        reservation.attempted(OffsetDateTime.now(clock));
        return reservation;
    }

    @Transactional
    BookingReservation needsAttention(long reservationId, String reason) {
        BookingReservation reservation = repository.findById(reservationId).orElseThrow();
        reservation.needsAttention(reason);
        return reservation;
    }

    /** Cancelled at LiteAPI: the booking is marked cancelled in the app, in the same transaction. */
    @Transactional
    BookingReservation cancelled(long reservationId, BigDecimal refundAmount, @Nullable String note) {
        BookingReservation reservation = repository.findById(reservationId).orElseThrow();
        reservation.cancelled(refundAmount, note, OffsetDateTime.now(clock));
        if (reservation.getBookingId() != null) {
            bookingLogic.cancel(reservation.getAppUserId(), reservation.getBookingId());
        }
        return reservation;
    }

    @Transactional
    BookingReservation refunded(long reservationId, String refundId) {
        BookingReservation reservation = repository.findById(reservationId).orElseThrow();
        reservation.refunded(refundId);
        return reservation;
    }

    @Transactional
    BookingReservation awaitPayment(long reservationId, String orderId) {
        BookingReservation reservation = repository.findById(reservationId).orElseThrow();
        reservation.awaitPayment(orderId);
        return reservation;
    }

    @Transactional
    BookingReservation authorized(long reservationId, String authorizationId) {
        BookingReservation reservation = repository.findById(reservationId).orElseThrow();
        reservation.authorized(authorizationId);
        return reservation;
    }

    @Transactional
    BookingReservation providerBooked(long reservationId, String providerBookingId, String confirmation) {
        BookingReservation reservation = repository.findById(reservationId).orElseThrow();
        reservation.providerBooked(providerBookingId, confirmation);
        return reservation;
    }

    @Transactional
    BookingReservation failed(long reservationId, String reason) {
        BookingReservation reservation = repository.findById(reservationId).orElseThrow();
        reservation.failed(reason);
        return reservation;
    }

    /** Creates the booking at the stop (source {@code SEARCH}) and closes the reservation, in one transaction. */
    @Transactional
    BookingView booked(long appUserId, long reservationId, String captureId, String replacedName) {
        BookingReservation reservation = repository.findById(reservationId).orElseThrow();
        ReservationHotel hotel = reservation.getHotel();
        String reference = reservation.getProviderConfirmation() != null ? reservation.getProviderConfirmation()
                : reservation.getProviderBookingId();
        BookingView booking = bookingLogic.createAccommodation(appUserId, reservation.getStopId(),
                new NewAccommodationBooking(BookingSource.SEARCH, hotel.name(), hotel.address(), hotel.countryCode(),
                        hotel.latitude(), hotel.longitude(), AlternativeSearch.PLACE_REF_PREFIX + reservation.getHotelId(),
                        hotel.phone(), hotel.email(), hotel.website(), "LiteAPI", reference, null,
                        reservation.getCheckIn().atTime(hotel.checkInFrom() == null ? DEFAULT_CHECK_IN : hotel.checkInFrom()),
                        reservation.getCheckOut().atTime(hotel.checkOutUntil() == null ? DEFAULT_CHECK_OUT : hotel.checkOutUntil()),
                        null, hotel.checkInFrom(), hotel.checkInUntil(), hotel.checkOutUntil(), reservation.getGuests(),
                        reservation.getRooms(), reservation.getPrice(), reservation.getCurrency(),
                        reservation.isRefundable() ? reservation.getFreeCancellationUntil() : null,
                        "In der App gebucht als Alternative zu „" + replacedName + "“, bezahlt mit PayPal. "
                                + "LiteAPI-Buchung " + reservation.getProviderBookingId() + "."));
        reservation.booked(captureId, booking.booking().getId(), OffsetDateTime.now(clock));
        return booking;
    }
}
