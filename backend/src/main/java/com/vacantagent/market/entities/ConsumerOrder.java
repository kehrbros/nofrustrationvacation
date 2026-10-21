package com.vacantagent.market.entities;

import com.vacantagent.common.data.AbstractEntity;
import com.vacantagent.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;

import java.time.Duration;

@Getter
@Entity
public class ConsumerOrder extends AbstractEntity {
    @Column(precision = 19, scale = Money.SCALE)
    private Money minCostPerMillionTokens;
    @Column(precision = 19, scale = Money.SCALE)
    private Money maxCostPerMillionTokens;
    private Duration marketWaitTime;
}
