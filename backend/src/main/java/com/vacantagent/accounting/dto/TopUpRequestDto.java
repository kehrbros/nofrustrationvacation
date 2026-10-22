package com.vacantagent.accounting.dto;

import com.vacantagent.currency.dto.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;

/** @param amount amount to pay at PayPal, in a supported currency with at most its decimal places */
public record TopUpRequestDto(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto amount) {
}
