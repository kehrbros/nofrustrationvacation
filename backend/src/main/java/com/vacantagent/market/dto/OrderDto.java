package com.vacantagent.market.dto;

import com.vacantagent.currency.dto.MoneyDto;

import java.time.Duration;

public record OrderDto(MoneyDto maxCostPerMillionInputTokens, MoneyDto maxCostPerMillionOutputTokens,
                       Duration marketWaitTime, String modelProvider, String modelName) {
}