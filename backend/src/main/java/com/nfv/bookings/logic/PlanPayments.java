package com.nfv.bookings.logic;

import com.nfv.bookings.config.AlternativesProperties;
import com.nfv.bookings.data.PlanPaymentRepository;
import com.nfv.bookings.entities.BookingReservation;
import com.nfv.bookings.entities.PlanPayment;
import com.nfv.bookings.entities.PlanPaymentStatus;
import com.nfv.bookings.entities.ReservationStatus;
import com.nfv.paypal.PayPalAmount;
import com.nfv.paypal.PayPalCheckout;
import com.nfv.paypal.PayPalRejectedException;
import com.nfv.trips.logic.TripLogic;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pays all hotels of a plan with one approval at PayPal, see Docs/incident-plans.md#one-click-execution: every
 * hotel is prebooked, one order with a purchase unit per hotel; after the approval all are authorized and booked at
 * LiteAPI ({@link #secure}). If one hotel fails, the others are cancelled at LiteAPI and every authorization voided,
 * nothing is paid and the trip stays as it is. Only then the plan changes the trip (the caller, {@code advisor}) and
 * {@link #settle} captures and creates the bookings at the stops the plan made. Each step is committed before the next
 * external call; the background job continues where a request stopped ({@link #recover}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanPayments {
    private static final Set<String> NOT_APPROVED = Set.of("ORDER_NOT_APPROVED", "PAYER_ACTION_REQUIRED");

    private final PlanPaymentRepository repository;
    private final AlternativeReservations reservations;
    private final ReservationSteps steps;
    private final TripLogic tripLogic;
    private final PayPalCheckout checkout;
    private final LiteApi liteApi;
    private final AlternativesProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /**
     * A hotel of the plan.
     *
     * @param key               names the stay in the plan, e.g. {@code overnight}, comes back in the view
     * @param stopId            where it is booked for now; a stay at a stop the plan creates moves there in {@link #settle}
     * @param replacesBookingId the booking it replaces, it goes on the to-do list once booked
     * @param maxPrice          the price the plan showed, a higher one after prebooking fails
     */
    public record PlanStay(String key, long stopId, @Nullable Long replacesBookingId, String hotelId, String offerId,
                           LocalDate checkIn, LocalDate checkOut, int guests, int rooms, @Nullable BigDecimal maxPrice,
                           String currency) {
    }

    /** The person the hotels are booked for. */
    public record Guest(String firstName, String lastName, String email, String phone) {
    }

    /** @param approveUrl only right after {@link #reserve}, for the redirect fallback */
    public record PlanPaymentView(long planPaymentId, PlanPaymentStatus status, @Nullable String orderId,
                                  @Nullable String approveUrl, BigDecimal total, String currency,
                                  @Nullable String failure, List<Item> items) {
    }

    /** @param bookingId once settled */
    public record Item(String key, long reservationId, String hotelName, BigDecimal price, String currency,
                       ReservationStatus status, long stopId, @Nullable Long bookingId) {
    }

    /** The money is reserved and every hotel booked: the plan can change the trip. Also from the background job. */
    public record PlanPaymentSecured(long planPaymentId, long appUserId) {
    }

    /**
     * Prebooks every hotel and creates one PayPal order for all of them. Nothing is paid or booked yet.
     *
     * @param pagePath page of the frontend the redirect fallback of PayPal comes back to
     * @throws OfferUnavailableException     an offer is gone, the plan has to be searched again
     * @throws PriceExceededException        an offer costs more than the plan showed
     * @throws InvalidBookingInputException  guest data, dates or currencies do not fit
     * @throws BookingNotFoundException      a stop is unknown or of another user
     */
    public PlanPaymentView reserve(long appUserId, List<PlanStay> stays, Guest guest, String pagePath) {
        if (stays.isEmpty()) {
            throw new InvalidBookingInputException("a plan payment needs at least one stay");
        }
        if (stays.stream().map(PlanStay::currency).distinct().count() != 1) {
            throw new InvalidBookingInputException("the stays of a plan must have one currency");
        }
        List<AlternativeReservations.NewReservation> inputs = stays.stream().map(stay -> {
            tripLogic.stop(appUserId, stay.stopId()).orElseThrow(() -> BookingNotFoundException.stop(stay.stopId()));
            return new AlternativeReservations.NewReservation(stay.hotelId(), stay.offerId(), stay.checkIn(),
                    stay.checkOut(), stay.guests(), stay.rooms(), stay.maxPrice(), stay.currency(), guest.firstName(),
                    guest.lastName(), guest.email(), guest.phone());
        }).toList();
        BookingReservation.Holder holder = AlternativeReservations.validate(inputs.getFirst());
        checkout.requireConfigured();
        String currency = stays.getFirst().currency();
        long planPaymentId = Objects.requireNonNull(transactionTemplate.execute(_ ->
                repository.save(new PlanPayment(appUserId, BigDecimal.ZERO, currency, OffsetDateTime.now(clock)))
                        .getId()));

        List<BookingReservation> prebooked = new ArrayList<>();
        try {
            for (int i = 0; i < stays.size(); i++) {
                PlanStay stay = stays.get(i);
                AlternativeReservations.validate(inputs.get(i));
                prebooked.add(reservations.prebooked(appUserId, stay.stopId(), stay.replacesBookingId(),
                        inputs.get(i), holder, planPaymentId, stay.key()));
            }
        } catch (RuntimeException e) {
            // a prebooked rate is only held, nothing to release at LiteAPI
            prebooked.forEach(reservation -> steps.failed(reservation.getId(), "another hotel of the plan failed"));
            transactionTemplate.executeWithoutResult(_ -> repository.findById(planPaymentId).orElseThrow()
                    .failed("prebook failed: " + e.getMessage()));
            throw e;
        }
        BigDecimal total = prebooked.stream().map(BookingReservation::getPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
        String page = properties.frontendUrl() + pagePath;
        PayPalCheckout.CheckoutOrder order;
        try {
            order = checkout.createOrder("plan-" + planPaymentId, prebooked.stream()
                            .map(reservation -> new PayPalCheckout.Unit(AlternativeReservations.reference(reservation),
                                    new PayPalAmount(reservation.getCurrency(), reservation.getPrice()),
                                    AlternativeReservations.description(reservation.getHotel().name(),
                                            reservation.getCheckIn(), reservation.getCheckOut())))
                            .toList(),
                    URI.create(page + "?planzahlung=" + planPaymentId),
                    URI.create(page + "?planzahlung=" + planPaymentId + "&abgebrochen=1"));
        } catch (PayPalRejectedException e) {
            prebooked.forEach(reservation -> steps.failed(reservation.getId(), "PayPal order refused: " + e.getIssue()));
            transactionTemplate.executeWithoutResult(_ -> repository.findById(planPaymentId).orElseThrow()
                    .failed("PayPal order refused: " + e.getIssue()));
            throw new PaymentFailedException("PAYMENT_DECLINED", "PayPal refused the order");
        }
        prebooked.forEach(reservation -> steps.awaitPayment(reservation.getId(), order.orderId()));
        transactionTemplate.executeWithoutResult(_ -> {
            PlanPayment payment = repository.findById(planPaymentId).orElseThrow();
            payment.total(total);
            payment.awaitPayment(order.orderId());
        });
        return view(planPaymentId, order.approveUrl());
    }

    /**
     * After the approval at PayPal: authorizes every hotel and books it at LiteAPI. Safe to repeat. If a hotel cannot
     * be booked, the ones booked before are cancelled at LiteAPI, every authorization is voided and the payment fails.
     * Publishes {@link PlanPaymentSecured} once secured.
     *
     * @throws PaymentFailedException      not approved yet ({@code PAYMENT_NOT_APPROVED}) or declined
     * @throws OfferUnavailableException   a hotel refused the booking, nothing is paid
     * @throws LiteApiUnavailableException state at LiteAPI unclear, try again later
     */
    public PlanPaymentView secure(long appUserId, long planPaymentId) {
        PlanPayment payment = owned(appUserId, planPaymentId);
        if (payment.getStatus() == PlanPaymentStatus.PAYMENT_PENDING) {
            secure(payment);
        }
        return view(planPaymentId, null);
    }

    private void secure(PlanPayment payment) {
        long planPaymentId = payment.getId();
        attempted(planPaymentId);
        List<BookingReservation> stays = steps.ofPlanPayment(planPaymentId);
        if (stays.stream().anyMatch(r -> r.getStatus() == ReservationStatus.PAYMENT_PENDING)) {
            Map<String, PayPalCheckout.Payment> authorizations;
            try {
                authorizations = checkout.authorizeUnits(Objects.requireNonNull(payment.getPayPalOrderId()),
                        "authorize-plan-" + planPaymentId);
            } catch (PayPalRejectedException e) {
                if (NOT_APPROVED.contains(e.getIssue())) {
                    throw new PaymentFailedException("PAYMENT_NOT_APPROVED", "payment not approved at PayPal yet");
                }
                fail(planPaymentId, stays, "PayPal declined: " + e.getIssue());
                throw new PaymentFailedException("PAYMENT_DECLINED", "payment declined by PayPal");
            }
            for (BookingReservation reservation : stays) {
                if (reservation.getStatus() != ReservationStatus.PAYMENT_PENDING) {
                    continue;
                }
                PayPalCheckout.Payment authorization = authorizations.get(AlternativeReservations.reference(reservation));
                if (authorization == null || authorization.amount() != null
                        && (!authorization.amount().currency().equals(reservation.getCurrency())
                        || authorization.amount().value().compareTo(reservation.getPrice()) != 0)) {
                    authorizations.values().forEach(other -> voidQuietly(other.id(), planPaymentId));
                    fail(planPaymentId, steps.ofPlanPayment(planPaymentId), "authorization missing or differs");
                    throw new PaymentFailedException("PAYMENT_DECLINED", "authorized amount differs from the price");
                }
                steps.authorized(reservation.getId(), authorization.id());
            }
        }
        for (BookingReservation reservation : steps.ofPlanPayment(planPaymentId)) {
            if (reservation.getStatus() != ReservationStatus.AUTHORIZED) {
                continue;
            }
            try {
                reservations.book(reservation);
            } catch (OfferUnavailableException e) {
                // book voided this one and marked it failed; undo the others
                undo(planPaymentId, "hotel " + reservation.getHotel().name() + " refused: " + e.getMessage());
                throw e;
            }
        }
        transactionTemplate.executeWithoutResult(_ -> repository.findById(planPaymentId).orElseThrow()
                .secured(OffsetDateTime.now(clock)));
        events.publishEvent(new PlanPaymentSecured(planPaymentId, payment.getAppUserId()));
    }

    /**
     * After the plan changed the trip: each hotel moves to the stop the plan gives for its key (a stay without one stays
     * where it is), the money is captured and the booking created there. Safe to repeat.
     *
     * @param stopIdByKey the stop of each stay, e.g. the new stop for the night on the way
     */
    public PlanPaymentView settle(long appUserId, long planPaymentId, Map<String, Long> stopIdByKey) {
        PlanPayment payment = owned(appUserId, planPaymentId);
        if (payment.getStatus() != PlanPaymentStatus.SECURED) {
            return view(planPaymentId, null);
        }
        for (BookingReservation reservation : steps.ofPlanPayment(planPaymentId)) {
            if (reservation.getStatus() != ReservationStatus.PROVIDER_BOOKED) {
                continue;
            }
            Long stopId = reservation.getPlanKey() == null ? null : stopIdByKey.get(reservation.getPlanKey());
            if (stopId != null && stopId != reservation.getStopId()) {
                reservation = steps.assignStop(reservation.getId(), stopId);
            }
            String captureId = reservations.capture(reservation);
            if (captureId != null) {
                steps.booked(appUserId, reservation.getId(), captureId, null);
            }
        }
        transactionTemplate.executeWithoutResult(_ -> repository.findById(planPaymentId).orElseThrow()
                .settled(OffsetDateTime.now(clock)));
        return view(planPaymentId, null);
    }

    /** The user cancelled at PayPal or closed the dialog: nothing was paid. */
    public PlanPaymentView abort(long appUserId, long planPaymentId) {
        PlanPayment payment = owned(appUserId, planPaymentId);
        if (payment.isOpen()) {
            fail(planPaymentId, steps.ofPlanPayment(planPaymentId), "cancelled by the user");
        }
        return view(planPaymentId, null);
    }

    public PlanPaymentView get(long appUserId, long planPaymentId) {
        owned(appUserId, planPaymentId);
        return view(planPaymentId, null);
    }

    /**
     * Background: a payment approved at PayPal is secured although nobody came back (the plan then changes the trip
     * through {@link PlanPaymentSecured}), a secured one that was not settled is announced again, one not approved in
     * time fails. Errors are left for the next run.
     */
    public void recover(long planPaymentId) {
        PlanPayment payment = transactionTemplate.execute(_ -> repository.findById(planPaymentId).orElseThrow());
        try {
            switch (Objects.requireNonNull(payment).getStatus()) {
                case PAYMENT_PENDING -> {
                    String orderStatus = checkout.getOrder(Objects.requireNonNull(payment.getPayPalOrderId())).status();
                    if ("APPROVED".equals(orderStatus) || "COMPLETED".equals(orderStatus)) {
                        secure(payment);
                    } else if (payment.getCreatedAt().isBefore(OffsetDateTime.now(clock)
                            .minus(properties.approvalTimeout()))) {
                        fail(planPaymentId, steps.ofPlanPayment(planPaymentId),
                                "payment not approved in time (" + orderStatus + ")");
                    }
                }
                case SECURED -> {
                    attempted(planPaymentId);
                    events.publishEvent(new PlanPaymentSecured(planPaymentId, payment.getAppUserId()));
                }
                case PREBOOKED -> fail(planPaymentId, steps.ofPlanPayment(planPaymentId), "no PayPal order");
                default -> {
                }
            }
        } catch (RuntimeException e) {
            log.warn("Plan payment {} not continued in the background: {}", planPaymentId, e.getMessage());
        }
    }

    /** Plan payments the background job may have to continue, with the last time someone worked on them. */
    List<PlanPayment> unfinished() {
        return Objects.requireNonNull(transactionTemplate.execute(_ -> repository.findByStatusIn(
                EnumSet.of(PlanPaymentStatus.PREBOOKED, PlanPaymentStatus.PAYMENT_PENDING, PlanPaymentStatus.SECURED))));
    }

    /** Cancels what was booked at LiteAPI and voids every authorization: nothing is paid. */
    private void undo(long planPaymentId, String reason) {
        for (BookingReservation reservation : steps.ofPlanPayment(planPaymentId)) {
            if (reservation.getStatus() == ReservationStatus.PROVIDER_BOOKED) {
                try {
                    LiteApi.Cancellation cancellation = liteApi.cancel(reservation.getProviderBookingId());
                    if (!cancellation.isCancelled()) {
                        steps.needsAttention(reservation.getId(), "plan failed, LiteAPI cancellation "
                                + cancellation.status());
                        continue;
                    }
                } catch (RuntimeException e) {
                    log.error("Plan payment {}: hotel {} booked but not cancelled: {}", planPaymentId,
                            reservation.getId(), e.getMessage());
                    steps.needsAttention(reservation.getId(), "plan failed, cancel at LiteAPI: " + e.getMessage());
                    continue;
                }
                reservations.voidQuietly(reservation.getPayPalAuthorizationId(), reservation);
                steps.providerCancelled(reservation.getId(), reason);
            } else if (reservation.getStatus() == ReservationStatus.AUTHORIZED) {
                reservations.voidQuietly(reservation.getPayPalAuthorizationId(), reservation);
                steps.failed(reservation.getId(), reason);
            } else if (reservation.getStatus() == ReservationStatus.PREBOOKED
                    || reservation.getStatus() == ReservationStatus.PAYMENT_PENDING) {
                steps.failed(reservation.getId(), reason);
            }
        }
        transactionTemplate.executeWithoutResult(_ -> repository.findById(planPaymentId).orElseThrow()
                .failed(reason));
    }

    private void fail(long planPaymentId, List<BookingReservation> stays, String reason) {
        stays.stream().filter(r -> r.getStatus() == ReservationStatus.PREBOOKED
                        || r.getStatus() == ReservationStatus.PAYMENT_PENDING)
                .forEach(r -> steps.failed(r.getId(), reason));
        transactionTemplate.executeWithoutResult(_ -> {
            PlanPayment payment = repository.findById(planPaymentId).orElseThrow();
            if (payment.isOpen()) {
                payment.failed(reason);
            }
        });
    }

    private void voidQuietly(String authorizationId, long planPaymentId) {
        try {
            checkout.voidAuthorization(authorizationId, "void-" + authorizationId);
        } catch (RuntimeException e) {
            log.warn("Void for plan payment {} failed: {}", planPaymentId, e.getMessage());
        }
    }

    private void attempted(long planPaymentId) {
        transactionTemplate.executeWithoutResult(_ -> repository.findById(planPaymentId).orElseThrow()
                .attempted(OffsetDateTime.now(clock)));
    }

    private PlanPayment owned(long appUserId, long planPaymentId) {
        return Objects.requireNonNull(transactionTemplate.execute(_ -> repository.findById(planPaymentId)
                .filter(payment -> payment.getAppUserId() == appUserId)
                .orElseThrow(() -> new ReservationNotFoundException(planPaymentId))));
    }

    private PlanPaymentView view(long planPaymentId, @Nullable String approveUrl) {
        return Objects.requireNonNull(transactionTemplate.execute(_ -> {
            PlanPayment payment = repository.findById(planPaymentId).orElseThrow();
            List<Item> items = steps.ofPlanPayment(planPaymentId).stream()
                    .map(r -> new Item(Objects.requireNonNullElse(r.getPlanKey(), ""), r.getId(), r.getHotel().name(),
                            r.getPrice(), r.getCurrency(), r.getStatus(), r.getStopId(), r.getBookingId()))
                    .toList();
            return new PlanPaymentView(planPaymentId, payment.getStatus(), payment.getPayPalOrderId(), approveUrl,
                    payment.getTotal(), payment.getCurrency(), payment.getFailure(), items);
        }));
    }
}
