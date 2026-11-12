package com.nfv.bookings.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * Attempt to book an alternative accommodation via LiteAPI, paid with PayPal: prebooked offer with our price, the
 * hotel as it will become the booking, the steps of payment and booking, see Docs/accommodation-alternatives.md.
 * Not audited: the holder's contact data is kept only until the booking is made.
 */
@Getter
@Entity
public class BookingReservation extends AbstractEntity {
    /** Owner (of the trip), for the completion in the background. */
    @Column(nullable = false)
    private long appUserId;
    /** {@code null} once the stop is deleted while the reservation is booked and paid, see {@link #detachFromStop}. */
    private @Nullable Long stopId;
    /** The booking that cannot be used, if this replaces one. */
    private @Nullable Long replacesBookingId;
    /** The booking created once everything succeeded. */
    private @Nullable Long bookingId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status;

    @Column(nullable = false, length = 64)
    private String hotelId;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String offerId;
    @Column(nullable = false)
    private String prebookId;
    @Column(nullable = false)
    private LocalDate checkIn;
    @Column(nullable = false)
    private LocalDate checkOut;
    private int guests;
    private int rooms;
    /** Net rate at LiteAPI, charged to our account. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal netPrice;
    /** Our commission, {@code price = netPrice + fee}. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal fee;
    /** What the user pays with PayPal. */
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal price;
    @Column(nullable = false, length = 3)
    private String currency;
    private boolean refundable;
    /** Local at the hotel. */
    private @Nullable LocalDateTime freeCancellationUntil;

    @Embedded
    private ReservationHotel hotel;

    @Column(length = 100)
    private @Nullable String holderFirstName;
    @Column(length = 100)
    private @Nullable String holderLastName;
    private @Nullable String holderEmail;
    @Column(length = 64)
    private @Nullable String holderPhone;

    @Column(name = "paypal_order_id", length = 64)
    private @Nullable String payPalOrderId;
    @Column(name = "paypal_authorization_id", length = 64)
    private @Nullable String payPalAuthorizationId;
    @Column(name = "paypal_capture_id", length = 64)
    private @Nullable String payPalCaptureId;
    private @Nullable String providerBookingId;
    private @Nullable String providerConfirmation;
    @Column(length = 500)
    private @Nullable String failure;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    private @Nullable OffsetDateTime bookedAt;
    /** Last time a request or the background job worked on it. */
    private @Nullable OffsetDateTime attemptedAt;
    private @Nullable OffsetDateTime cancelledAt;
    /** What is refunded with PayPal after a cancellation, zero if nothing. */
    @Column(precision = 12, scale = 2)
    private @Nullable BigDecimal refundAmount;
    @Column(name = "paypal_refund_id", length = 64)
    private @Nullable String payPalRefundId;

    /** The payment of a plan with several hotels this belongs to, see Docs/incident-plans.md. */
    private @Nullable Long planPaymentId;
    /** Which stay of the plan, e.g. {@code overnight}. */
    @Column(length = 32)
    private @Nullable String planKey;

    protected BookingReservation() {
    }

    /** Part of the payment of a plan, before the PayPal order. */
    public void partOf(long planPaymentId, @Nullable String planKey) {
        require(ReservationStatus.PREBOOKED);
        this.planPaymentId = planPaymentId;
        this.planKey = planKey;
    }

    /**
     * The plan created the stop of the stay (a night on the way) or moved the stay to it: the booking is made there.
     * Only before the booking exists.
     */
    public void assignStop(long stopId) {
        if (status == ReservationStatus.BOOKED || status == ReservationStatus.CANCELLED) {
            throw new IllegalStateException("reservation " + getId() + " is " + status);
        }
        this.stopId = stopId;
    }

    /** True while money is held for it: booked and paid, or cancelled with the refund still due. */
    public boolean holdsMoney() {
        return status == ReservationStatus.BOOKED || isRefundOpen();
    }

    /**
     * Its stop is deleted, but the money is still held: the reservation stays without a stop, so it can be cancelled
     * with a refund from the to-do list.
     */
    public void detachFromStop() {
        if (!holdsMoney()) {
            throw new IllegalStateException("reservation " + getId() + " is " + status);
        }
        this.stopId = null;
    }

    public BookingReservation(long appUserId, long stopId, @Nullable Long replacesBookingId, Offer offer,
                              ReservationHotel hotel, Holder holder, OffsetDateTime createdAt) {
        this.appUserId = appUserId;
        this.stopId = stopId;
        this.replacesBookingId = replacesBookingId;
        this.status = ReservationStatus.PREBOOKED;
        this.hotelId = offer.hotelId();
        this.offerId = offer.offerId();
        this.prebookId = offer.prebookId();
        this.checkIn = offer.checkIn();
        this.checkOut = offer.checkOut();
        this.guests = offer.guests();
        this.rooms = offer.rooms();
        this.netPrice = offer.netPrice();
        this.fee = offer.price().subtract(offer.netPrice());
        this.price = offer.price();
        this.currency = offer.currency();
        this.refundable = offer.refundable();
        this.freeCancellationUntil = offer.freeCancellationUntil();
        this.hotel = hotel;
        this.holderFirstName = holder.firstName();
        this.holderLastName = holder.lastName();
        this.holderEmail = holder.email();
        this.holderPhone = holder.phone();
        this.createdAt = createdAt;
    }

    /** PayPal order created, the user approves it at PayPal. */
    public void awaitPayment(String payPalOrderId) {
        require(ReservationStatus.PREBOOKED);
        this.payPalOrderId = payPalOrderId;
        this.status = ReservationStatus.PAYMENT_PENDING;
    }

    /** The money is reserved at PayPal. */
    public void authorized(String payPalAuthorizationId) {
        require(ReservationStatus.PAYMENT_PENDING);
        this.payPalAuthorizationId = payPalAuthorizationId;
        this.status = ReservationStatus.AUTHORIZED;
    }

    /** Booked at LiteAPI (charged to our account), the money is still to be captured. */
    public void providerBooked(String providerBookingId, @Nullable String confirmation) {
        require(ReservationStatus.AUTHORIZED);
        this.providerBookingId = providerBookingId;
        this.providerConfirmation = confirmation;
        this.status = ReservationStatus.PROVIDER_BOOKED;
    }

    /** Remembers that a request or the background job works on it now. */
    public void attempted(OffsetDateTime at) {
        this.attemptedAt = at;
    }

    /**
     * Booked at LiteAPI, but the money could not be moved or the booking state is unclear: the booking is kept (or
     * may exist), no automatic step is taken any more.
     */
    public void needsAttention(String reason) {
        if (status != ReservationStatus.AUTHORIZED && status != ReservationStatus.PROVIDER_BOOKED) {
            throw new IllegalStateException("reservation " + getId() + " is " + status);
        }
        this.failure = reason.length() > 500 ? reason.substring(0, 500) : reason;
        this.status = ReservationStatus.NEEDS_ATTENTION;
    }

    /** Cancelled at LiteAPI; {@code refundAmount} is refunded with PayPal next (zero: nothing). */
    public void cancelled(BigDecimal refundAmount, @Nullable String note, OffsetDateTime at) {
        require(ReservationStatus.BOOKED);
        this.refundAmount = refundAmount;
        this.cancelledAt = at;
        this.status = ReservationStatus.CANCELLED;
        if (note != null) {
            this.failure = note.length() > 500 ? note.substring(0, 500) : note;
        }
    }

    public void refunded(String payPalRefundId) {
        require(ReservationStatus.CANCELLED);
        this.payPalRefundId = payPalRefundId;
    }

    /** Cancelled, a refund is due and not made yet. */
    public boolean isRefundOpen() {
        return status == ReservationStatus.CANCELLED && payPalRefundId == null && refundAmount != null
                && refundAmount.signum() > 0;
    }

    /** Money captured, booking created; the holder's data is no longer needed. */
    public void booked(String payPalCaptureId, long bookingId, OffsetDateTime at) {
        require(ReservationStatus.PROVIDER_BOOKED);
        this.payPalCaptureId = payPalCaptureId;
        this.bookingId = bookingId;
        this.bookedAt = at;
        this.status = ReservationStatus.BOOKED;
        forgetHolder();
    }

    /** Nothing booked, no money moved (authorization voided or never made). */
    public void failed(String reason) {
        if (status == ReservationStatus.BOOKED || status == ReservationStatus.PROVIDER_BOOKED) {
            throw new IllegalStateException("reservation " + getId() + " is booked already");
        }
        this.failure = reason.length() > 500 ? reason.substring(0, 500) : reason;
        this.status = ReservationStatus.FAILED;
        forgetHolder();
    }

    /**
     * Booked at LiteAPI as part of a plan, but another hotel of the plan failed: cancelled at LiteAPI again before the
     * money was captured, the authorization voided. Nothing paid.
     */
    public void providerCancelled(String reason) {
        require(ReservationStatus.PROVIDER_BOOKED);
        this.failure = reason.length() > 500 ? reason.substring(0, 500) : reason;
        this.status = ReservationStatus.FAILED;
        forgetHolder();
    }

    private void forgetHolder() {
        this.holderFirstName = null;
        this.holderLastName = null;
        this.holderEmail = null;
        this.holderPhone = null;
    }

    private void require(ReservationStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("reservation " + getId() + " is " + status + ", not " + expected);
        }
    }

    /** The prebooked offer with our price. */
    public record Offer(String hotelId, String offerId, String prebookId, LocalDate checkIn, LocalDate checkOut,
                        int guests, int rooms, BigDecimal netPrice, BigDecimal price, String currency,
                        boolean refundable, @Nullable LocalDateTime freeCancellationUntil) {
    }

    public record Holder(String firstName, String lastName, String email, String phone) {
    }
}
