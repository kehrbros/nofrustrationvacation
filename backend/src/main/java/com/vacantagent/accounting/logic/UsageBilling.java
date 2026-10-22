package com.vacantagent.accounting.logic;

import com.vacantagent.accounting.entities.AccountType;
import com.vacantagent.accounting.entities.EntryType;
import com.vacantagent.common.money.Money;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles LLM usage between consumer and provider. Entry point for the LLM proxy, which is not implemented yet,
 * see Docs/accounting.md.
 */
@Service
@RequiredArgsConstructor
public class UsageBilling {
    private final AccountLedger ledger;

    /**
     * Moves {@code cost} from the consumer's credit to the provider's earnings, both or neither.
     *
     * @throws InsufficientBalanceException if the consumer's credit does not cover the cost
     */
    @Transactional
    public void settle(long agreementId, long consumerAppUserId, long providerAppUserId, Money cost) {
        if (cost.isZero()) {
            return;
        }
        ledger.debit(consumerAppUserId, AccountType.CREDIT, cost, EntryType.USAGE_CHARGE, agreementId);
        ledger.credit(providerAppUserId, AccountType.EARNINGS, cost, EntryType.USAGE_EARNING, agreementId);
    }
}
