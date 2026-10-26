package com.vacantagent.market.entities;

import com.vacantagent.common.data.AbstractEntity;
import com.vacantagent.common.money.Money;
import com.vacantagent.user.entities.AppUser;
import jakarta.persistence.CascadeType;
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

    /**
     * Created together with the offer. Fetched eagerly: the order book keeps offers outside a transaction and
     * reads the model from it.
     */
    @ManyToOne(cascade = CascadeType.PERSIST)
    private Agent agent;

    private OffsetDateTime lastErrorAt;
    private OffsetDateTime lastSuccessAt;

    private Long availableTokens;
    private Long totalTokens;

    /** Who offers it; set from the authenticated request. */
    @ManyToOne(fetch = FetchType.LAZY)
    private AppUser appUser;

    protected ProviderOffer() {
    }
}
