package com.vacantagent.market.entities;

import com.vacantagent.common.data.AbstractEntity;
import com.vacantagent.common.money.Money;
import com.vacantagent.user.entities.AppUser;
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

    /** Who placed it; set from the authenticated request. */
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser appUser;

    protected ConsumerOrder() {
    }
}
