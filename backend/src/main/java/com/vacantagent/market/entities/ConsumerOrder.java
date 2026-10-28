package com.vacantagent.market.entities;

import com.vacantagent.common.data.AbstractEntity;
import com.vacantagent.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Duration;
import java.time.OffsetDateTime;

@Getter
@Entity
@AllArgsConstructor
public class ConsumerOrder extends AbstractEntity {
    @Column(precision = 19, scale = Money.SCALE)
    private Money maxCostPerMillionInputTokens;
    @Column(precision = 19, scale = Money.SCALE)
    private Money maxCostPerMillionOutputTokens;
    private Duration marketWaitTime;
    /** Start of {@link #marketWaitTime}. */
    private OffsetDateTime createdAt;

    private String modelProvider;
    private String modelName;

    /** Last call through the agreement. */
    private OffsetDateTime lastSuccessAt;

    /** Who placed it; set from the authenticated request. {@code null} for orders placed before owners were stored. */
    private Long appUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    private MarketScheduling marketScheduling;

    protected ConsumerOrder() {
    }

    /** Call only while the agreement is not idle, see {@code Agreement#isIdleSince}. */
    public void recordSuccess(OffsetDateTime at) {
        this.lastSuccessAt = at;
    }
}
