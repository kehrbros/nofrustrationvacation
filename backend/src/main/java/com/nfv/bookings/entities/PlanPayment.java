package com.nfv.bookings.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One PayPal payment for all hotels of a plan, see Docs/incident-plans.md#one-click-execution. The hotels are
 * {@link BookingReservation}s that point here; each has its own purchase unit and authorization in the one order.
 * Not audited, like the reservations.
 */
@Getter
@Entity
public class PlanPayment extends AbstractEntity {
    @Column(nullable = false)
    private long appUserId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PlanPaymentStatus status = PlanPaymentStatus.PREBOOKED;
    @Column(name = "paypal_order_id", length = 64)
    private @Nullable String payPalOrderId;
    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal total;
    @Column(nullable = false, length = 3)
    private String currency;
    @Column(length = 500)
    private @Nullable String failure;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    private @Nullable OffsetDateTime securedAt;
    private @Nullable OffsetDateTime settledAt;
    /** Last time a request or the background job worked on it. */
    private @Nullable OffsetDateTime attemptedAt;

    protected PlanPayment() {
    }

    public PlanPayment(long appUserId, BigDecimal total, String currency, OffsetDateTime createdAt) {
        this.appUserId = appUserId;
        this.total = total;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    /** Sum of the locked prices, once every hotel is prebooked. */
    public void total(BigDecimal total) {
        require(PlanPaymentStatus.PREBOOKED);
        this.total = total;
    }

    public void awaitPayment(String payPalOrderId) {
        require(PlanPaymentStatus.PREBOOKED);
        this.payPalOrderId = payPalOrderId;
        this.status = PlanPaymentStatus.PAYMENT_PENDING;
    }

    /** Authorized and every hotel booked at LiteAPI: the trip can change now, the money is captured after. */
    public void secured(OffsetDateTime at) {
        require(PlanPaymentStatus.PAYMENT_PENDING);
        this.status = PlanPaymentStatus.SECURED;
        this.securedAt = at;
    }

    /** Captured and the bookings created, or left for someone to look at. */
    public void settled(OffsetDateTime at) {
        require(PlanPaymentStatus.SECURED);
        this.status = PlanPaymentStatus.SETTLED;
        this.settledAt = at;
    }

    public void attempted(OffsetDateTime at) {
        this.attemptedAt = at;
    }

    /** Nothing booked and nothing paid: the trip stays as it is. */
    public void failed(String reason) {
        if (status == PlanPaymentStatus.SETTLED || status == PlanPaymentStatus.SECURED) {
            throw new IllegalStateException("plan payment " + getId() + " is " + status);
        }
        this.failure = reason.length() > 500 ? reason.substring(0, 500) : reason;
        this.status = PlanPaymentStatus.FAILED;
    }

    public boolean isOpen() {
        return status == PlanPaymentStatus.PREBOOKED || status == PlanPaymentStatus.PAYMENT_PENDING;
    }

    private void require(PlanPaymentStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("plan payment " + getId() + " is " + status + ", not " + expected);
        }
    }
}
