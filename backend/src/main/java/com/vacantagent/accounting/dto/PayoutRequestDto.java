package com.vacantagent.accounting.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;

/**
 * @param currency ISO 4217 code of the currency to receive at PayPal
 * @param amount   amount in {@code currency}, {@code null} pays out all earnings
 */
public record PayoutRequestDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "EUR") String currency,
        @Nullable
        @Schema(type = "string", format = "decimal", example = "50.00")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount) {
}
