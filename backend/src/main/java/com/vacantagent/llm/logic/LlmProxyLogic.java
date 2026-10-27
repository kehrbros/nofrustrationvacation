package com.vacantagent.llm.logic;

import com.vacantagent.accounting.entities.AccountType;
import com.vacantagent.accounting.logic.AccountLedger;
import com.vacantagent.common.money.Money;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Business rules of the LLM proxy as checks; the flow is in {@code LlmProxyService}, see Docs/llm.md. Rules on a
 * single entity's fields sit on the entity, e.g. {@code ProviderOffer#hasTokensLeft}, {@code Agreement#costFor}.
 */
@Service
@RequiredArgsConstructor
public class LlmProxyLogic {
    private final AccountLedger ledger;

    public boolean isPromptValid(@Nullable String prompt) {
        return prompt != null && !prompt.isBlank();
    }

    /** Consumers may only prompt while their credit is above zero; the call's cost may then take it below. */
    public boolean hasPositiveCredit(long appUserId) {
        return ledger.balance(appUserId, AccountType.CREDIT).isGreaterThan(Money.ZERO);
    }
}