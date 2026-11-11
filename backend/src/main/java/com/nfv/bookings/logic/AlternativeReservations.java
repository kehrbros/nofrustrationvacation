package com.nfv.bookings.logic;

import com.nfv.bookings.config.AlternativesProperties;
import com.nfv.bookings.entities.BookingReservation;
import com.nfv.bookings.entities.ReservationHotel;
import com.nfv.bookings.entities.ReservationStatus;
import com.nfv.paypal.PayPalAmount;
import com.nfv.paypal.PayPalCheckout;
import com.nfv.paypal.PayPalException;
import com.nfv.paypal.PayPalRejectedException;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.logic.TripEditing;
import com.nfv.trips.logic.TripLogic;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Books an alternative accommodation: prebook at LiteAPI, PayPal order (intent {@code AUTHORIZE}), after the user's
 * approval authorize, book at LiteAPI with our account, capture; if booking fails the authorization is voided, so no
 * money moves. Each step is committed before the next external call; {@link #complete} picks up where a previous
 * attempt stopped. See Docs/accommodation-alternatives.md#payment.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlternativeReservations {
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    /** LiteAPI offer ids are long (over 1000 characters seen, see V51); only a guard against absurd input. */
    private static final int MAX_OFFER_ID = 20_000;
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9 ()/-]{6,30}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    /** Issues after which the user may approve again or another payment method may work: no reason to fail. */
    private static final Set<String> NOT_APPROVED = Set.of("ORDER_NOT_APPROVED", "PAYER_ACTION_REQUIRED");
    private static final String DUPLICATE_REFERENCE = "4005";

    private final BookingLogic bookingLogic;
    private final TripLogic tripLogic;
    private final TripEditing tripEditing;
    private final LiteApi liteApi;
    private final PayPalCheckout checkout;
    private final ReservationSteps steps;
    private final AlternativesProperties properties;
    private final Clock clock;

    /**
     * Prebooks the offer and creates the PayPal order. The user then approves the payment at
     * {@link ReservationView#approveUrl()}; nothing is paid or booked yet.
     *
     * @throws OfferUnavailableException the offer is gone, search again
     * @throws PriceExceededException    the locked price is above {@code maxPrice}
     */
    public ReservationView reserve(long appUserId, long bookingId, NewReservation input) {
        BookingView current = bookingLogic.accommodation(appUserId, bookingId);
        Stop stop = tripLogic.stop(appUserId, current.booking().getStopId())
                .orElseThrow(() -> BookingNotFoundException.booking(bookingId));
        return reserve(appUserId, stop, bookingId, input, "/reisen/" + stop.getTrip().getId() + "/buchungen/"
                + bookingId + "/alternativen");
    }

    /**
     * Like {@link #reserve(long, long, NewReservation)}, for a stop without accommodation to replace, e.g. a night on
     * the way that the user took from a proposal of the agent (Docs/advisor.md#accepting-an-overnight-stay). The booking
     * is created at the stop.
     *
     * @throws BookingNotFoundException unknown stop or one of another user
     */
    public ReservationView reserveAtStop(long appUserId, long stopId, NewReservation input) {
        Stop stop = tripLogic.stop(appUserId, stopId).orElseThrow(() -> BookingNotFoundException.stop(stopId));
        return reserve(appUserId, stop, null, input, "/reisen/" + stop.getTrip().getId() + "/stops/" + stopId
                + "/unterkunft");
    }

    /** @param pagePath page of the frontend the redirect fallback of PayPal comes back to */
    private ReservationView reserve(long appUserId, Stop stop, @Nullable Long bookingId, NewReservation input,
                                    String pagePath) {
        long tripId = stop.getTrip().getId();
        BookingReservation.Holder holder = validate(input);
        checkout.requireConfigured();
        BookingReservation reservation = prebooked(appUserId, stop.getId(), bookingId, input, holder, null, null);
        String hotelName = reservation.getHotel().name();

        String page = properties.frontendUrl() + pagePath;
        PayPalCheckout.CheckoutOrder order;
        try {
            order = checkout.createOrder(reference(reservation), new PayPalAmount(reservation.getCurrency(),
                            reservation.getPrice()),
                    description(hotelName, input.checkIn(), input.checkOut()), PayPalCheckout.Intent.AUTHORIZE,
                    URI.create(page + "?reservierung=" + reservation.getId()),
                    URI.create(page + "?reservierung=" + reservation.getId() + "&abgebrochen=1"));
        } catch (PayPalRejectedException e) {
            steps.failed(reservation.getId(), "PayPal order refused: " + e.getIssue());
            throw new PaymentFailedException("PAYMENT_DECLINED", "PayPal refused the order");
        }
        reservation = steps.awaitPayment(reservation.getId(), order.orderId());
        return new ReservationView(reservation, tripId, order.approveUrl(), null);
    }

    /**
     * Prebooks the offer at LiteAPI and stores the reservation with the locked price, nothing paid yet.
     *
     * @param planPaymentId the payment of a plan the reservation belongs to, {@code null} on its own
     */
    BookingReservation prebooked(long appUserId, long stopId, @Nullable Long bookingId, NewReservation input,
                                 BookingReservation.Holder holder, @Nullable Long planPaymentId,
                                 @Nullable String planKey) {
        LiteApi.Prebook prebook = liteApi.prebook(input.offerId());
        if (!prebook.currency().equalsIgnoreCase(input.currency())) {
            throw new OfferUnavailableException("currency", "offer is in " + prebook.currency());
        }
        BigDecimal price = Pricing.price(prebook.netPrice(), prebook.suggestedSellingPrice(), properties.feePercent());
        if (input.maxPrice() != null && price.compareTo(input.maxPrice()) > 0) {
            throw new PriceExceededException(price.toPlainString(), input.maxPrice().toPlainString());
        }
        LiteApi.HotelDetails hotel = liteApi.hotel(input.hotelId());
        return steps.create(appUserId, stopId, bookingId,
                new BookingReservation.Offer(input.hotelId(), input.offerId(), prebook.prebookId(), input.checkIn(),
                        input.checkOut(), input.guests(), input.rooms(), prebook.netPrice(), price,
                        prebook.currency().toUpperCase(), prebook.refundable(), prebook.freeCancellationUntil()),
                new ReservationHotel(hotel.name(), address(hotel), hotel.countryCode(), hotel.latitude(),
                        hotel.longitude(), hotel.phone(), hotel.email(), hotel.website(), hotel.checkInFrom(),
                        hotel.checkInUntil(), hotel.checkOutUntil()),
                holder, planPaymentId, planKey);
    }

    /**
     * After the user approved at PayPal: authorize, book at LiteAPI, capture, create the booking. Safe to repeat,
     * continues from the last committed step; the background job ({@link ReservationRecovery}) does the same for
     * reservations nobody comes back to.
     *
     * @throws PaymentFailedException     not approved or declined, nothing booked
     * @throws OfferUnavailableException  LiteAPI refused the booking, the authorization is voided
     * @throws LiteApiUnavailableException outcome at LiteAPI unknown, try again later
     */
    public ReservationView complete(long appUserId, long reservationId) {
        BookingReservation reservation = steps.owned(appUserId, reservationId);
        long tripId = tripId(appUserId, reservation);
        Advanced advanced = advance(reservation);
        BookingReservation current = advanced.reservation();
        BookingView booking = current.getBookingId() == null ? null
                : bookingLogic.accommodation(appUserId, current.getBookingId());
        return new ReservationView(current, tripId, null, booking, advanced.replannedRouteIds());
    }

    /** Reservations of a trip that are not finished: payment running, or booked but to be checked. */
    public List<ReservationView> unfinished(long appUserId, long tripId) {
        List<Long> stopIds = tripLogic.stops(appUserId, tripId).stream().map(Stop::getId).toList();
        OffsetDateTime approvalLimit = OffsetDateTime.now(clock).minus(properties.approvalTimeout());
        return steps.ofStops(stopIds).stream()
                .filter(reservation -> switch (reservation.getStatus()) {
                    case PAYMENT_PENDING -> reservation.getCreatedAt().isAfter(approvalLimit);
                    case AUTHORIZED, PROVIDER_BOOKED, NEEDS_ATTENTION -> true;
                    default -> false;
                })
                .map(reservation -> new ReservationView(reservation, tripId, null, null))
                .toList();
    }

    /**
     * Continues a reservation in the background: a payment approved at PayPal is booked although the user did not
     * come back (closed the app, lost the connection), one that was not approved in time fails, a due refund is
     * made. Errors are left for the next run.
     */
    public void recover(long reservationId) {
        BookingReservation reservation = steps.find(reservationId);
        try {
            if (reservation.isRefundOpen()) {
                refund(reservation);
                return;
            }
            if (reservation.getStatus() == ReservationStatus.PAYMENT_PENDING) {
                String orderStatus = checkout.getOrder(reservation.getPayPalOrderId()).status();
                if (!"APPROVED".equals(orderStatus) && !"COMPLETED".equals(orderStatus)) {
                    if (reservation.getCreatedAt().isBefore(OffsetDateTime.now(clock).minus(properties.approvalTimeout()))) {
                        steps.failed(reservationId, "payment not approved in time (" + orderStatus + ")");
                    }
                    return;
                }
            }
            advance(reservation);
            log.info("Reservation {} continued in the background", reservationId);
        } catch (RuntimeException e) {
            log.warn("Reservation {} not continued in the background: {}", reservationId, e.getMessage());
        }
    }

    /**
     * Cancels an alternative booked in the app at LiteAPI and refunds the payment with PayPal: in full while the
     * cancellation is free, otherwise what LiteAPI refunds (nothing if it does not say).
     *
     * @throws CancellationNotPossibleException not booked in the app or no longer free to cancel
     */
    public ReservationView cancel(long appUserId, long bookingId) {
        BookingReservation reservation = steps.ofBooking(appUserId, bookingId)
                .orElseThrow(() -> new CancellationNotPossibleException("booking " + bookingId
                        + " was not booked in the app"));
        long tripId = tripId(appUserId, reservation);
        if (reservation.getStatus() == ReservationStatus.BOOKED) {
            if (!reservation.isRefundable()) {
                throw new CancellationNotPossibleException("the rate is not refundable");
            }
            LiteApi.Cancellation cancellation = liteApi.cancel(reservation.getProviderBookingId());
            if (!cancellation.isCancelled()) {
                throw new LiteApiUnavailableException("LiteAPI answered the cancellation with " + cancellation.status());
            }
            BigDecimal refund = cancellation.isFree() ? reservation.getPrice()
                    : cancellation.refund() == null ? BigDecimal.ZERO : cancellation.refund().min(reservation.getPrice());
            reservation = steps.cancelled(reservation.getId(), refund, cancellation.isFree() ? null
                    : "cancelled with charges, LiteAPI refund " + cancellation.refund());
        }
        if (reservation.isRefundOpen()) {
            try {
                reservation = refund(reservation);
            } catch (PayPalException e) {
                // cancelled at the hotel; the background job refunds later
                log.warn("Refund of reservation {} failed, retried later: {}", reservation.getId(), e.getMessage());
            }
        }
        return view(appUserId, steps.find(reservation.getId()), tripId);
    }

    /** The user cancelled at PayPal or left: the reservation fails, nothing was paid. */
    public ReservationView abort(long appUserId, long reservationId) {
        BookingReservation reservation = steps.owned(appUserId, reservationId);
        if (reservation.getStatus() == ReservationStatus.PREBOOKED
                || reservation.getStatus() == ReservationStatus.PAYMENT_PENDING) {
            reservation = steps.failed(reservationId, "cancelled by the user");
        }
        return view(appUserId, reservation, tripId(appUserId, reservation));
    }

    public ReservationView get(long appUserId, long reservationId) {
        BookingReservation reservation = steps.owned(appUserId, reservationId);
        return view(appUserId, reservation, tripId(appUserId, reservation));
    }

    private BookingReservation refund(BookingReservation reservation) {
        PayPalCheckout.Payment refund = checkout.refundCapture(reservation.getPayPalCaptureId(),
                reservation.getRefundAmount().compareTo(reservation.getPrice()) == 0 ? null
                        : new PayPalAmount(reservation.getCurrency(), reservation.getRefundAmount()),
                "refund-" + reference(reservation));
        return steps.refunded(reservation.getId(), refund.id());
    }

    /** @param replannedRouteIds routes planned again because the stop moved to the new place, see {@link #followStay} */
    private record Advanced(BookingReservation reservation, List<Long> replannedRouteIds) {
    }

    private Advanced advance(BookingReservation reservation) {
        long reservationId = reservation.getId();
        if (reservation.getStatus() == ReservationStatus.PAYMENT_PENDING
                || reservation.getStatus() == ReservationStatus.AUTHORIZED
                || reservation.getStatus() == ReservationStatus.PROVIDER_BOOKED) {
            reservation = steps.attempted(reservationId);
        }
        if (reservation.getStatus() == ReservationStatus.PAYMENT_PENDING) {
            reservation = authorize(reservation);
        }
        if (reservation.getStatus() == ReservationStatus.AUTHORIZED) {
            reservation = book(reservation);
        }
        if (reservation.getStatus() == ReservationStatus.PROVIDER_BOOKED) {
            String captureId = capture(reservation);
            if (captureId == null) {
                return new Advanced(steps.find(reservationId), List.of());
            }
            long appUserId = reservation.getAppUserId();
            String replacedName = reservation.getReplacesBookingId() == null ? null
                    : replacedName(appUserId, reservation.getReplacesBookingId());
            steps.booked(appUserId, reservationId, captureId, replacedName);
            reservation = steps.find(reservationId);
            return new Advanced(reservation, followStay(reservation));
        }
        return new Advanced(reservation, List.of());
    }

    /**
     * After booking: if the hotel lies in another place than the stop, the stop moves there (named after the town) and
     * its routes are planned again, see Docs/accommodation-alternatives.md#route-after-rebooking. The booking stays
     * whatever happens here.
     *
     * @return the routes planned again, empty within the same place or if moving failed
     */
    private List<Long> followStay(BookingReservation reservation) {
        ReservationHotel hotel = reservation.getHotel();
        if (hotel.latitude() == null || hotel.longitude() == null) {
            return List.of();
        }
        try {
            Map<Long, ?> planned = tripEditing.moveStopToStay(reservation.getAppUserId(), reservation.getStopId(),
                    hotel.latitude(), hotel.longitude(), town(reservation));
            if (!planned.isEmpty()) {
                log.info("Stop {} moved to the place of reservation {}", reservation.getStopId(), reservation.getId());
            }
            return List.copyOf(planned.keySet());
        } catch (RuntimeException e) {
            log.warn("Stop {} not moved after reservation {}: {}", reservation.getStopId(), reservation.getId(),
                    e.getMessage());
            return List.of();
        }
    }

    /** Name of the replaced accommodation, a placeholder if it is gone meanwhile. */
    private String replacedName(long appUserId, long bookingId) {
        try {
            return bookingLogic.accommodation(appUserId, bookingId).accommodation().getName();
        } catch (BookingNotFoundException | InvalidBookingInputException e) {
            return "die bisherige Unterkunft";
        }
    }

    /** Town of the hotel from LiteAPI's hotel data (not kept in the reservation), {@code null} if unknown. */

    private @Nullable String town(BookingReservation reservation) {
        try {
            return liteApi.hotel(reservation.getHotelId()).city();
        } catch (RuntimeException e) {
            log.warn("Town of hotel {} unknown: {}", reservation.getHotelId(), e.getMessage());
            return null;
        }
    }

    private BookingReservation authorize(BookingReservation reservation) {
        PayPalCheckout.Payment authorization;
        try {
            authorization = checkout.authorize(reservation.getPayPalOrderId(), "authorize-" + reference(reservation));
        } catch (PayPalRejectedException e) {
            if (NOT_APPROVED.contains(e.getIssue())) {
                throw new PaymentFailedException("PAYMENT_NOT_APPROVED", "payment not approved at PayPal yet");
            }
            steps.failed(reservation.getId(), "PayPal declined: " + e.getIssue());
            throw new PaymentFailedException("PAYMENT_DECLINED", "payment declined by PayPal");
        }
        PayPalAmount expected = new PayPalAmount(reservation.getCurrency(), reservation.getPrice());
        if (authorization.amount() != null && (!authorization.amount().currency().equals(expected.currency())
                || authorization.amount().value().compareTo(expected.value()) != 0)) {
            voidQuietly(authorization.id(), reservation);
            steps.failed(reservation.getId(), "authorized amount " + authorization.amount() + " differs");
            throw new PaymentFailedException("PAYMENT_DECLINED", "authorized amount differs from the price");
        }
        return steps.authorized(reservation.getId(), authorization.id());
    }

    BookingReservation book(BookingReservation reservation) {
        if (reservation.getHolderFirstName() == null) {
            throw new IllegalStateException("reservation " + reservation.getId() + " has no holder");
        }
        LiteApi.ProviderBooking booked;
        try {
            booked = liteApi.book(reservation.getPrebookId(), new LiteApi.Holder(reservation.getHolderFirstName(),
                            reservation.getHolderLastName(), reservation.getHolderEmail(), reservation.getHolderPhone()),
                    reservation.getRooms(), clientReference(reservation));
        } catch (OfferUnavailableException e) {
            if (DUPLICATE_REFERENCE.equals(e.getProviderCode())) {
                // booked by an earlier attempt whose answer got lost: continue with that booking
                LiteApi.ProviderBooking earlier = liteApi.findBooking(clientReference(reservation)).orElseThrow(() -> {
                    log.warn("Reservation {} is booked at LiteAPI already, booking not found yet", reservation.getId());
                    return new LiteApiUnavailableException("booking state at LiteAPI unclear, try again later");
                });
                log.info("Reservation {} was booked at LiteAPI before ({})", reservation.getId(), earlier.bookingId());
                return steps.providerBooked(reservation.getId(), earlier.bookingId(), earlier.hotelConfirmationCode());
            }
            voidQuietly(reservation.getPayPalAuthorizationId(), reservation);
            steps.failed(reservation.getId(), e.getMessage());
            throw e;
        }
        return steps.providerBooked(reservation.getId(), booked.bookingId(), booked.hotelConfirmationCode());
    }

    /**
     * @return the capture id; null if PayPal refused it: the room is booked (and charged to us), the reservation
     * needs attention instead of turning into a booking without payment
     */
    @Nullable String capture(BookingReservation reservation) {
        PayPalCheckout.Payment capture;
        try {
            capture = checkout.captureAuthorization(reservation.getPayPalAuthorizationId(),
                    "capture-" + reference(reservation));
        } catch (PayPalRejectedException e) {
            log.error("Capture of reservation {} refused: {}", reservation.getId(), e.getIssue());
            steps.needsAttention(reservation.getId(), "PayPal refused the capture: " + e.getIssue());
            return null;
        }
        if (!capture.isCompleted() && !capture.isPending()) {
            log.error("Capture of reservation {} is {}", reservation.getId(), capture.status());
            steps.needsAttention(reservation.getId(), "PayPal capture " + capture.status());
            return null;
        }
        return capture.id();
    }

    void voidQuietly(String authorizationId, BookingReservation reservation) {
        try {
            checkout.voidAuthorization(authorizationId, "void-" + reference(reservation));
        } catch (PayPalException e) {
            // the authorization expires by itself after some days
            log.warn("Void of reservation {} failed: {}", reservation.getId(), e.getMessage());
        }
    }

    private ReservationView view(long appUserId, BookingReservation reservation, long tripId) {
        BookingView booking = reservation.getBookingId() == null ? null
                : bookingLogic.accommodation(appUserId, reservation.getBookingId());
        return new ReservationView(reservation, tripId, null, booking);
    }

    private long tripId(long appUserId, BookingReservation reservation) {
        return tripLogic.stop(appUserId, reservation.getStopId()).map(stop -> stop.getTrip().getId())
                .orElseThrow(() -> new ReservationNotFoundException(reservation.getId()));
    }

    static String reference(BookingReservation reservation) {
        return "stay-" + reservation.getId();
    }

    static String clientReference(BookingReservation reservation) {
        return "nfv-" + reference(reservation);
    }

    static String description(String hotel, LocalDate checkIn, LocalDate checkOut) {
        String text = hotel + ", " + DATE.format(checkIn) + " – " + DATE.format(checkOut);
        return text.length() > 127 ? text.substring(0, 127) : text;
    }

    private static @Nullable String address(LiteApi.HotelDetails hotel) {
        String address = hotel.address() == null ? hotel.city()
                : hotel.city() == null || hotel.address().contains(hotel.city()) ? hotel.address()
                : hotel.address() + ", " + hotel.city();
        return address == null || address.length() <= 500 ? address : address.substring(0, 500);
    }

    static BookingReservation.Holder validate(NewReservation input) {
        if (input.hotelId() == null || input.hotelId().isBlank() || input.hotelId().length() > 64
                || input.offerId() == null || input.offerId().isBlank() || input.offerId().length() > MAX_OFFER_ID) {
            throw new InvalidBookingInputException("hotel and offer are required");
        }
        if (input.checkIn() == null || input.checkOut() == null || !input.checkOut().isAfter(input.checkIn())
                || input.rooms() < 1 || input.guests() < input.rooms()) {
            throw new InvalidBookingInputException("dates, guests and rooms are required");
        }
        if (input.currency() == null || !input.currency().matches("[A-Z]{3}")) {
            throw new InvalidBookingInputException("currency must be ISO 4217");
        }
        String firstName = required(input.firstName(), 100, "firstName");
        String lastName = required(input.lastName(), 100, "lastName");
        String email = required(input.email(), 255, "email");
        String phone = required(input.phone(), 64, "phone");
        if (!EMAIL.matcher(email).matches()) {
            throw new InvalidBookingInputException("email", "email is invalid");
        }
        if (!PHONE.matcher(phone).matches()) {
            throw new InvalidBookingInputException("phone", "phone is invalid");
        }
        return new BookingReservation.Holder(firstName, lastName, email, phone);
    }

    private static String required(@Nullable String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidBookingInputException(field, field + " is required");
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new InvalidBookingInputException(field, field + " has at most " + maxLength + " characters");
        }
        return stripped;
    }

    /**
     * The offer the user picked from the search, with the criteria it was found with and the person it is booked
     * for (passed to LiteAPI, kept only until the booking is made).
     */
    public record NewReservation(String hotelId, String offerId, LocalDate checkIn, LocalDate checkOut, int guests,
                                 int rooms, @Nullable BigDecimal maxPrice, String currency, String firstName,
                                 String lastName, String email, String phone) {
    }
}
