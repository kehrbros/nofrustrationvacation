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
import com.nfv.trips.logic.TripLogic;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Books an alternative accommodation: prebook at LiteAPI, PayPal order (intent {@code AUTHORIZE}), after the user's
 * approval authorize, book at LiteAPI with our account, capture; if booking fails the authorization is voided, so no
 * money moves. Each step is committed before the next external call; {@link #complete} picks up where a previous
 * attempt stopped. See Docs/accommodation-alternatives.md#zahlung.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlternativeReservations {
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9 ()/-]{6,30}");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    /** Issues after which the user may approve again or another payment method may work: no reason to fail. */
    private static final Set<String> NOT_APPROVED = Set.of("ORDER_NOT_APPROVED", "PAYER_ACTION_REQUIRED");
    private static final String DUPLICATE_REFERENCE = "4005";

    private final BookingLogic bookingLogic;
    private final TripLogic tripLogic;
    private final LiteApi liteApi;
    private final PayPalCheckout checkout;
    private final ReservationSteps steps;
    private final AlternativesProperties properties;

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
        long tripId = stop.getTrip().getId();
        BookingReservation.Holder holder = validate(input);
        checkout.requireConfigured();

        LiteApi.Prebook prebook = liteApi.prebook(input.offerId());
        if (!prebook.currency().equalsIgnoreCase(input.currency())) {
            throw new OfferUnavailableException("currency", "offer is in " + prebook.currency());
        }
        BigDecimal price = Pricing.price(prebook.netPrice(), prebook.suggestedSellingPrice(), properties.feePercent());
        if (input.maxPrice() != null && price.compareTo(input.maxPrice()) > 0) {
            throw new PriceExceededException(price.toPlainString(), input.maxPrice().toPlainString());
        }
        LiteApi.HotelDetails hotel = liteApi.hotel(input.hotelId());
        BookingReservation reservation = steps.create(stop.getId(), bookingId,
                new BookingReservation.Offer(input.hotelId(), input.offerId(), prebook.prebookId(), input.checkIn(),
                        input.checkOut(), input.guests(), input.rooms(), prebook.netPrice(), price,
                        prebook.currency().toUpperCase(), prebook.refundable(), prebook.freeCancellationUntil()),
                new ReservationHotel(hotel.name(), address(hotel), hotel.countryCode(), hotel.latitude(),
                        hotel.longitude(), hotel.phone(), hotel.email(), hotel.website(), hotel.checkInFrom(),
                        hotel.checkInUntil(), hotel.checkOutUntil()),
                holder);

        String page = properties.frontendUrl() + "/reisen/" + tripId + "/buchungen/" + bookingId + "/alternativen";
        PayPalCheckout.CheckoutOrder order;
        try {
            order = checkout.createOrder(reference(reservation), new PayPalAmount(reservation.getCurrency(),
                            reservation.getPrice()),
                    description(hotel.name(), input.checkIn(), input.checkOut()), PayPalCheckout.Intent.AUTHORIZE,
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
     * After the user approved at PayPal: authorize, book at LiteAPI, capture, create the booking. Safe to repeat,
     * continues from the last committed step.
     *
     * @throws PaymentFailedException     not approved or declined, nothing booked
     * @throws OfferUnavailableException  LiteAPI refused the booking, the authorization is voided
     * @throws LiteApiUnavailableException outcome at LiteAPI unknown, try again later
     */
    public ReservationView complete(long appUserId, long reservationId) {
        BookingReservation reservation = steps.owned(appUserId, reservationId);
        long tripId = tripId(appUserId, reservation);
        if (reservation.getStatus() == ReservationStatus.PAYMENT_PENDING) {
            reservation = authorize(reservation);
        }
        if (reservation.getStatus() == ReservationStatus.AUTHORIZED) {
            reservation = book(reservation);
        }
        if (reservation.getStatus() == ReservationStatus.PROVIDER_BOOKED) {
            String captureId = capture(reservation);
            String replacedName = reservation.getReplacesBookingId() == null ? "die bisherige Unterkunft"
                    : bookingLogic.accommodation(appUserId, reservation.getReplacesBookingId()).accommodation().getName();
            steps.booked(appUserId, reservationId, captureId, replacedName);
            reservation = steps.owned(appUserId, reservationId);
        }
        return view(appUserId, reservation, tripId);
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

    private BookingReservation book(BookingReservation reservation) {
        if (reservation.getHolderFirstName() == null) {
            throw new IllegalStateException("reservation " + reservation.getId() + " has no holder");
        }
        LiteApi.ProviderBooking booked;
        try {
            booked = liteApi.book(reservation.getPrebookId(), new LiteApi.Holder(reservation.getHolderFirstName(),
                            reservation.getHolderLastName(), reservation.getHolderEmail(), reservation.getHolderPhone()),
                    reservation.getRooms(), "nfv-" + reference(reservation));
        } catch (OfferUnavailableException e) {
            if (DUPLICATE_REFERENCE.equals(e.getProviderCode())) {
                // booked by an earlier attempt whose answer got lost: keep the money reserved, needs a manual check
                log.error("Reservation {} is booked at LiteAPI already, booking id unknown", reservation.getId());
                throw new LiteApiUnavailableException("booking state at LiteAPI unclear");
            }
            voidQuietly(reservation.getPayPalAuthorizationId(), reservation);
            steps.failed(reservation.getId(), e.getMessage());
            throw e;
        }
        return steps.providerBooked(reservation.getId(), booked.bookingId(), booked.hotelConfirmationCode());
    }

    private String capture(BookingReservation reservation) {
        PayPalCheckout.Payment capture = checkout.captureAuthorization(reservation.getPayPalAuthorizationId(),
                "capture-" + reference(reservation));
        if (!capture.isCompleted() && !capture.isPending()) {
            // booked at LiteAPI, but PayPal refused the capture: the booking stays, needs a manual check
            log.error("Capture of reservation {} is {}", reservation.getId(), capture.status());
        }
        return capture.id();
    }

    private void voidQuietly(String authorizationId, BookingReservation reservation) {
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

    private static String reference(BookingReservation reservation) {
        return "stay-" + reservation.getId();
    }

    private static String description(String hotel, LocalDate checkIn, LocalDate checkOut) {
        String text = hotel + ", " + DATE.format(checkIn) + " – " + DATE.format(checkOut);
        return text.length() > 127 ? text.substring(0, 127) : text;
    }

    private static @Nullable String address(LiteApi.HotelDetails hotel) {
        String address = hotel.address() == null ? hotel.city()
                : hotel.city() == null || hotel.address().contains(hotel.city()) ? hotel.address()
                : hotel.address() + ", " + hotel.city();
        return address == null || address.length() <= 500 ? address : address.substring(0, 500);
    }

    private static BookingReservation.Holder validate(NewReservation input) {
        if (input.hotelId() == null || input.hotelId().isBlank() || input.hotelId().length() > 64
                || input.offerId() == null || input.offerId().isBlank() || input.offerId().length() > 1000) {
            throw new InvalidBookingInputException("hotel and offer are required");
        }
        if (input.checkIn() == null || input.checkOut() == null || !input.checkOut().isAfter(input.checkIn())
                || input.rooms() < 1 || input.guests() < input.rooms()) {
            throw new InvalidBookingInputException("dates, guests and rooms are required");
        }
        if (input.currency() == null || !input.currency().matches("[A-Z]{3}")) {
            throw new InvalidBookingInputException("currency must be ISO 4217");
        }
        String firstName = required(input.firstName(), 100, "first name");
        String lastName = required(input.lastName(), 100, "last name");
        String email = required(input.email(), 255, "e-mail");
        String phone = required(input.phone(), 64, "phone");
        if (!EMAIL.matcher(email).matches()) {
            throw new InvalidBookingInputException("e-mail is invalid");
        }
        if (!PHONE.matcher(phone).matches()) {
            throw new InvalidBookingInputException("phone is invalid");
        }
        return new BookingReservation.Holder(firstName, lastName, email, phone);
    }

    private static String required(@Nullable String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidBookingInputException(field + " is required");
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw new InvalidBookingInputException(field + " has at most " + maxLength + " characters");
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
