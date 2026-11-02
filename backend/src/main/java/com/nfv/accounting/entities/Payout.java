package com.nfv.accounting.entities;

import com.nfv.common.data.AbstractEntity;
import com.nfv.common.money.Money;
import com.nfv.common.money.OriginalAmount;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import org.hibernate.envers.Audited;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;

/** Provider withdraws earnings to the linked PayPal account. */
@Getter
@Entity
@Audited
public class Payout extends AbstractEntity {
    /** Payouts in these states still may move money. */
    public static final Set<PayoutStatus> OPEN = EnumSet.of(PayoutStatus.REQUESTED, PayoutStatus.SUBMITTED, PayoutStatus.UNKNOWN);

    @Column(nullable = false)
    private long appUserId;
    /** Debited amount in the base currency. */
    @Column(nullable = false, precision = 19, scale = Money.SCALE)
    private Money amount;
    /** Amount the receiver gets at PayPal. */
    @Embedded
    private OriginalAmount paidOut;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PayoutStatus status;
    /** PayPal payer id at the time of the request. */
    @Column(nullable = false)
    private String receiver;
    private @Nullable String paypalBatchId;
    private @Nullable String paypalTransactionStatus;
    private @Nullable String failureReason;
    @Column(nullable = false)
    private OffsetDateTime requestedAt;
    private @Nullable OffsetDateTime completedAt;

    protected Payout() {
    }

    public Payout(long appUserId, Money amount, OriginalAmount paidOut, String receiver, OffsetDateTime requestedAt) {
        this.appUserId = appUserId;
        this.amount = amount;
        this.paidOut = paidOut;
        this.receiver = receiver;
        this.status = PayoutStatus.REQUESTED;
        this.requestedAt = requestedAt;
    }

    /** Our unique id at PayPal ({@code sender_batch_id}), PayPal refuses to send it twice. */
    public String senderBatchId() {
        return "nfv-payout-" + getId();
    }

    public void submitted(String paypalBatchId, @Nullable String transactionStatus) {
        this.status = PayoutStatus.SUBMITTED;
        this.paypalBatchId = paypalBatchId;
        this.paypalTransactionStatus = transactionStatus;
    }

    public void stillOpen(@Nullable String transactionStatus) {
        this.paypalTransactionStatus = transactionStatus;
    }

    public void complete(@Nullable String transactionStatus, OffsetDateTime at) {
        this.status = PayoutStatus.COMPLETED;
        this.paypalTransactionStatus = transactionStatus;
        this.completedAt = at;
    }

    public void fail(String reason, OffsetDateTime at) {
        this.status = PayoutStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = at;
    }

    public void unknown(String reason) {
        this.status = PayoutStatus.UNKNOWN;
        this.failureReason = reason;
    }
}
