package com.vacantagent.llm.logic;

import com.vacantagent.accounting.entities.AccountType;
import com.vacantagent.accounting.logic.AccountLedger;
import com.vacantagent.common.money.Money;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The checks of the LLM proxy; the flow is covered by LlmProxyServiceTest. */
class LlmProxyLogicTest {
    private final AccountLedger ledger = mock(AccountLedger.class);
    private final LlmProxyLogic llmProxyLogic = new LlmProxyLogic(ledger);

    @Test
    void promptMustNotBeBlank() {
        assertThat(llmProxyLogic.isPromptValid("Hello")).isTrue();
        assertThat(llmProxyLogic.isPromptValid(null)).isFalse();
        assertThat(llmProxyLogic.isPromptValid("")).isFalse();
        assertThat(llmProxyLogic.isPromptValid("  ")).isFalse();
    }

    @Test
    void creditMustBeAboveZero() {
        assertThat(hasPositiveCredit("0.01")).isTrue();
        assertThat(hasPositiveCredit("0.00")).isFalse();
        assertThat(hasPositiveCredit("-0.30")).isFalse();
    }

    private boolean hasPositiveCredit(String balance) {
        when(ledger.balance(1L, AccountType.CREDIT)).thenReturn(Money.of(balance));
        return llmProxyLogic.hasPositiveCredit(1L);
    }
}