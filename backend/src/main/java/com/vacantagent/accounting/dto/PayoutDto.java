package com.vacantagent.accounting.dto;

import com.vacantagent.accounting.entities.PayoutStatus;
import com.vacantagent.currency.dto.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

/** @param paidOut amount sent to PayPal, with the exchange rate used */
public record PayoutDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PayoutStatus status,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto paidOut,
        @Nullable String failureReason,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime requestedAt,
        @Nullable OffsetDateTime completedAt) {
}
