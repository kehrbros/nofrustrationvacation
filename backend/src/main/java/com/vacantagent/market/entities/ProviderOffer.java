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

import java.time.OffsetDateTime;

@Getter
@Entity
@AllArgsConstructor
public class ProviderOffer extends AbstractEntity {
    @Column(precision = 19, scale = Money.SCALE)
    private Money pricePerMillionInputTokens;
    @Column(precision = 19, scale = Money.SCALE)
    private Money pricePerMillionOutputTokens;
    private OffsetDateTime offeredUntil;

    /** Agent of the agent module that serves the offer; its owner is the offer's owner. */
    private long agentId;

    /** Copied from the agent when the offer is placed: matching needs them without I/O. */
    private String modelProvider;
    private String modelName;

    private OffsetDateTime lastErrorAt;
    private OffsetDateTime lastSuccessAt;

    private Long availableTokens;
    private Long totalTokens;

    /** Who offers it; set from the authenticated request. */
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser appUser;

    protected ProviderOffer() {
    }

    /** {@code null} available tokens mean no limit. */
    public boolean hasTokensLeft() {
        return availableTokens == null || availableTokens > 0;
    }
}
