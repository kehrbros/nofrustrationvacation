package com.nfv.currency.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Monetary amount at the API boundary. Amounts are decimal strings, never JSON numbers, see Docs/money.md.
 *
 * @param exchangeRate     units of {@code currency} per one unit of the base currency, only set if converted
 * @param exchangeRateDate date of the exchange rate, only set if converted
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MoneyDto(
        @Schema(type = "string", format = "decimal", example = "12.50", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal amount,
        @Schema(description = "ISO 4217 currency code", example = "EUR", requiredMode = Schema.RequiredMode.REQUIRED)
        String currency,
        @Nullable
        @Schema(type = "string", format = "decimal", example = "0.9187000000")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        BigDecimal exchangeRate,
        @Nullable
        LocalDate exchangeRateDate) {

    public MoneyDto(BigDecimal amount, String currency) {
        this(amount, currency, null, null);
    }
}
