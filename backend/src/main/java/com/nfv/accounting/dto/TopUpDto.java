package com.nfv.accounting.dto;

import com.nfv.accounting.entities.TopUpStatus;
import com.nfv.currency.dto.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

/**
 * @param credited credited amount in the requested currency, at the rate of the top-up
 * @param paid     amount paid at PayPal, with the exchange rate used
 */
public record TopUpDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TopUpStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto credited,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto paid,
        @Nullable String paypalOrderId,
        @Nullable String failureReason,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime createdAt,
        @Nullable OffsetDateTime completedAt) {
}
