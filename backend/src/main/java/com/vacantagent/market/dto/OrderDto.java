package com.vacantagent.market.dto;

import com.vacantagent.currency.dto.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.Duration;

/** A new consumer order. It is matched against the cheapest offer for the requested model within its limits. */
public record OrderDto(
        @Schema(description = "Highest accepted price per million input tokens", requiredMode = Schema.RequiredMode.REQUIRED)
        MoneyDto maxCostPerMillionInputTokens,
        @Schema(description = "Highest accepted price per million output tokens", requiredMode = Schema.RequiredMode.REQUIRED)
        MoneyDto maxCostPerMillionOutputTokens,
        @Nullable
        @Schema(type = "string", format = "duration", example = "PT5M",
                description = "How long the order waits in the market for a fitting offer (ISO 8601). "
                        + "Missing means it waits until cancelled, zero means it only matches on arrival.")
        Duration marketWaitTime,
        @Nullable
        @Schema(example = "openai", description = "Only offers of this provider. Missing means any provider, "
                + "then modelName must be missing too.")
        String modelProvider,
        @Nullable
        @Schema(example = "gpt-5", description = "Only offers of this model. Missing means any model of modelProvider.")
        String modelName) {
}