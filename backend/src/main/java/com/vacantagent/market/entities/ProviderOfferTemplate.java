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
public class ProviderOfferTemplate extends AbstractEntity {
    @Column(precision = 19, scale = Money.SCALE)
    private Money pricePerMillionInputTokens;
    @Column(precision = 19, scale = Money.SCALE)
    private Money pricePerMillionOutputTokens;
    private OffsetDateTime offeredUntil;

    private long agentId;

    /** Cap on the offered tokens, {@code null} offers all unused tokens of the scheduling's cycle. */
    private Long totalTokens;

    @ManyToOne(fetch = FetchType.LAZY)
    private MarketScheduling marketScheduling;
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser appUser;

    protected ProviderOfferTemplate(){
    }
}
