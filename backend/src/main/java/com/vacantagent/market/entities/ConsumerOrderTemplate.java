package com.vacantagent.market.entities;

import com.vacantagent.common.data.AbstractEntity;
import com.vacantagent.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Duration;

@Getter
@Entity
@AllArgsConstructor
public class ConsumerOrderTemplate extends AbstractEntity {
    @Column(precision = 19, scale = Money.SCALE)
    private Money maxCostPerMillionInputTokens;
    @Column(precision = 19, scale = Money.SCALE)
    private Money maxCostPerMillionOutputTokens;
    private Duration marketWaitTime;

    private String modelProvider;
    private String modelName;

    public ConsumerOrderTemplate(){
    }
}
