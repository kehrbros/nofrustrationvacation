package com.vacantagent.llm.dto;

import com.vacantagent.common.money.Money;

/**
 * @param text        the agent's answer
 * @param agreementId agreement the prompt was sent and billed through
 * @param cost        charged to the consumer, in {@link Money#BASE_CURRENCY}
 */
public record LlmCompletion(String text, long agreementId, long promptTokens, long completionTokens, long totalTokens,
                            Money cost) {
}