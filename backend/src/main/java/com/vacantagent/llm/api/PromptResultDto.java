package com.vacantagent.llm.api;

import com.vacantagent.currency.dto.MoneyDto;

/**
 * Answer of the {@code prompt} MCP tool.
 *
 * @param agreementId agreement the prompt was sent and billed through
 * @param cost        charged to the consumer, in the user's preferred currency
 */
public record PromptResultDto(String text, long agreementId, long promptTokens, long completionTokens,
                              long totalTokens, MoneyDto cost) {
}