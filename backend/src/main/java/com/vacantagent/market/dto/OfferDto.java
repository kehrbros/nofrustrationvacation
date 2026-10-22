package com.vacantagent.market.dto;

import com.vacantagent.currency.dto.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

/** A new offer; all of its {@code totalTokens} start out available. */
public record OfferDto(
        @Schema(description = "Price per million input tokens", requiredMode = Schema.RequiredMode.REQUIRED)
        MoneyDto pricePerMillionInputTokens,
        @Schema(description = "Price per million output tokens", requiredMode = Schema.RequiredMode.REQUIRED)
        MoneyDto pricePerMillionOutputTokens,
        @Nullable
        @Schema(description = "End of the offer and of any agreement made from it. Missing means no end.")
        OffsetDateTime offeredUntil,
        @Schema(example = "openai", requiredMode = Schema.RequiredMode.REQUIRED)
        String modelProvider,
        @Schema(example = "gpt-5", requiredMode = Schema.RequiredMode.REQUIRED)
        String modelName,
        @Schema(example = "1000000", description = "Tokens offered in total")
        Long totalTokens) {
}