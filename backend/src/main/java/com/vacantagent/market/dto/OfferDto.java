package com.vacantagent.market.dto;

import com.vacantagent.currency.dto.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

/** A new offer; all of its {@code totalTokens} start out available. The model is the one of its agent. */
public record OfferDto(
        @Schema(description = "Price per million input tokens", requiredMode = Schema.RequiredMode.REQUIRED)
        MoneyDto pricePerMillionInputTokens,
        @Schema(description = "Price per million output tokens", requiredMode = Schema.RequiredMode.REQUIRED)
        MoneyDto pricePerMillionOutputTokens,
        @Nullable
        @Schema(description = "End of the offer and of any agreement made from it. Missing means no end.")
        OffsetDateTime offeredUntil,
        @Schema(example = "42", description = "Agent of the user that serves the offer",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Long agentId,
        @Schema(example = "1000000", description = "Tokens offered in total")
        Long totalTokens) {
}