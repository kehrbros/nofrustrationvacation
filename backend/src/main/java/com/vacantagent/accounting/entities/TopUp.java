package com.vacantagent.accounting.entities;

import com.vacantagent.common.data.AbstractEntity;
import com.vacantagent.common.money.Money;
import com.vacantagent.common.money.OriginalAmount;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import org.hibernate.envers.Audited;

import java.time.OffsetDateTime;

/** Consumer pays via PayPal, the {@link #amount} is credited to the {@link AccountType#CREDIT} account. */
@Getter
@Entity
@Audited
public class TopUp extends AbstractEntity {
    @Column(nullable = false)
    private long appUserId;
    /** Credited amount in the base currency, fixed when the top-up is started. */
    @Column(nullable = false, precision = 19, scale = Money.SCALE)
    private Money amount;
    /** Amount the payer pays at PayPal. */
    @Embedded
    private OriginalAmount paid;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TopUpStatus status;
    @Column(unique = true)
    private @Nullable String paypalOrderId;
    private @Nullable String paypalCaptureId;
    private @Nullable String failureReason;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    private @Nullable OffsetDateTime completedAt;

    protected TopUp() {
    }

    public TopUp(long appUserId, Money amount, OriginalAmount paid, OffsetDateTime createdAt) {
        this.appUserId = appUserId;
        this.amount = amount;
        this.paid = paid;
        this.status = TopUpStatus.CREATED;
        this.createdAt = createdAt;
    }

    public boolean isFinal() {
        return status == TopUpStatus.COMPLETED || status == TopUpStatus.CANCELLED || status == TopUpStatus.FAILED;
    }

    public void orderCreated(String paypalOrderId) {
        this.paypalOrderId = paypalOrderId;
    }

    public void pending(@Nullable String paypalCaptureId) {
        this.status = TopUpStatus.PENDING;
        this.paypalCaptureId = paypalCaptureId;
    }

    public void complete(@Nullable String paypalCaptureId, OffsetDateTime at) {
        this.status = TopUpStatus.COMPLETED;
        this.paypalCaptureId = paypalCaptureId;
        this.completedAt = at;
    }

    public void cancel(OffsetDateTime at) {
        this.status = TopUpStatus.CANCELLED;
        this.completedAt = at;
    }

    public void fail(String reason, OffsetDateTime at) {
        this.status = TopUpStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = at;
    }
}
