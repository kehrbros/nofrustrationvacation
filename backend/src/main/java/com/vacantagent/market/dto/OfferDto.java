package com.vacantagent.market.dto;

import com.vacantagent.currency.dto.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

/**
 * A new offer; all of its {@code totalTokens} start out available. Its agent is either an existing one
 * ({@code agentId}) or a new one from {@code modelProvider}, {@code modelName} and {@code secret}.
 */
public record OfferDto(
        @Schema(description = "Price per million input tokens", requiredMode = Schema.RequiredMode.REQUIRED)
        MoneyDto pricePerMillionInputTokens,
        @Schema(description = "Price per million output tokens", requiredMode = Schema.RequiredMode.REQUIRED)
        MoneyDto pricePerMillionOutputTokens,
        @Nullable
        @Schema(description = "End of the offer and of any agreement made from it. Missing means no end.")
        OffsetDateTime offeredUntil,
        @Nullable
        @Schema(example = "42", description = "Existing agent of the user. Replaces modelProvider, modelName and secret.")
        Long agentId,
        @Nullable
        @Schema(example = "openai", description = "Required without agentId")
        String modelProvider,
        @Nullable
        @Schema(example = "gpt-5", description = "Required without agentId")
        String modelName,
        @Nullable
        @Schema(description = "Credential for calling the offered agent. Required without agentId.")
        String secret,
        @Schema(example = "1000000", description = "Tokens offered in total")
        Long totalTokens) {
}