package com.vacantagent.market.entities;

import com.vacantagent.common.data.AbstractEntity;
import com.vacantagent.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

    private String modelProvider;
    private String modelName;

    private OffsetDateTime lastErrorAt;
    private OffsetDateTime lastSuccessAt;

    private Long availableTokens;
    private Long totalTokens;

    protected ProviderOffer() {
    }
}
