package com.vacantagent.market.dto;

import com.vacantagent.currency.dto.MoneyDto;

import java.time.OffsetDateTime;

/** A new offer; all of its {@code totalTokens} start out available. */
public record OfferDto(MoneyDto pricePerMillionInputTokens, MoneyDto pricePerMillionOutputTokens,
                       OffsetDateTime offeredUntil, String modelProvider, String modelName,
                       Long totalTokens) {
}