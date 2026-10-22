package com.vacantagent.accounting.dto;

import com.vacantagent.currency.dto.MoneyDto;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * @param credit   platform credit of the consumer, spent on LLM usage
 * @param earnings earnings of the provider, available for payout (rounded down)
 */
public record BalancesDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto credit,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MoneyDto earnings) {
}
