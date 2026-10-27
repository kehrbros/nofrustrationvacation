package com.vacantagent.llm.dto;

import com.vacantagent.currency.dto.MoneyDto;

import java.time.Duration;

/** Price limits and wait of the order a prompt places when the consumer has no fitting agreement. */
public record OrderLimits(MoneyDto maxCostPerMillionInputTokens, MoneyDto maxCostPerMillionOutputTokens,
                          Duration marketWaitTime) {
}