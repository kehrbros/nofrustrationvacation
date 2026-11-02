package com.nfv.accounting.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * Pending "Log in with PayPal" of a user. The {@link #state} travels through PayPal and back; it proves that the
 * returning authorization code belongs to a link the same user started (CSRF protection). Used once.
 */
@Getter
@Entity
public class PayoutAccountLinkState extends AbstractEntity {
    @Column(nullable = false, unique = true, length = 64)
    private String state;
    @Column(nullable = false)
    private long appUserId;
    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected PayoutAccountLinkState() {
    }

    public PayoutAccountLinkState(String state, long appUserId, OffsetDateTime createdAt) {
        this.state = state;
        this.appUserId = appUserId;
        this.createdAt = createdAt;
    }
}
