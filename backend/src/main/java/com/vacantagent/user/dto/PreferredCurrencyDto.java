package com.vacantagent.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

/**
 * @param currency ISO 4217 code, {@code null} resets to the base currency
 */
public record PreferredCurrencyDto(@Nullable @Schema(example = "EUR") String currency) {
}
