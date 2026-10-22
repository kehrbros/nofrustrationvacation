package com.vacantagent.market.dto;

import com.vacantagent.currency.dto.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

/** A matched order and offer. The consumer pays the provider's prices. */
public record AgreementDto(
        long id,
        long consumerOrderId,
        long providerOfferId,
        @Schema(example = "openai") String modelProvider,
        @Schema(example = "gpt-5") String modelName,
        OffsetDateTime agreedAt,
        @Nullable @Schema(description = "The offer's offeredUntil, missing means no end") OffsetDateTime validUntil,
        MoneyDto agreedPricePerMillionInputTokens,
        MoneyDto agreedPricePerMillionOutputTokens) {
}