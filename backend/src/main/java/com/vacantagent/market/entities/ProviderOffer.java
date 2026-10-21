package com.vacantagent.market.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Getter
@Entity
public class ProviderOffer extends AbstractEntity {
    @Column(scale = 6)
    private BigDecimal pricePerMillionInputTokens; // TODO PKE change to monetary value
    @Column(scale = 6)
    private BigDecimal pricePerMillionOutputTokens; // TODO PKE change to monetary value
    private OffsetDateTime offeredUntil;

    private String modelProvider;
    private String modelName;

    private OffsetDateTime lastErrorAt;
    private OffsetDateTime lastSuccessAt;
}
