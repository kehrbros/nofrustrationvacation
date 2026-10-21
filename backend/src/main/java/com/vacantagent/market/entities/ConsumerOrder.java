package com.vacantagent.market.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.Duration;

@Getter
@Entity
public class ConsumerOrder extends AbstractEntity {
    @Column(scale = 6)
    private BigDecimal minCostPerMillionTokens; // TODO PKE change to monetary value
    @Column(scale = 6)
    private BigDecimal maxCostPerMillionTokens; // TODO PKE change to monetary value
    private Duration marketWaitTime;
}
