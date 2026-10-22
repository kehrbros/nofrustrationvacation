package com.vacantagent.accounting;

import com.vacantagent.accounting.entities.AccountType;
import com.vacantagent.accounting.entities.EntryType;
import com.vacantagent.accounting.logic.AccountLedger;
import com.vacantagent.accounting.logic.InsufficientBalanceException;
import com.vacantagent.common.money.Money;
import com.vacantagent.user.logic.AppUserProvisioning;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Balances never go below zero and always equal the sum of their entries, also under concurrent bookings. */
@SpringBootTest
class AccountLedgerTest {
    @Autowired
    private AccountLedger ledger;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private AppUserProvisioning appUserProvisioning;

    @Test
    void debitBeyondTheBalanceBooksNothing() {
        long userId = newUser();
        book(() -> ledger.credit(userId, AccountType.EARNINGS, Money.of("10"), EntryType.USAGE_EARNING, 1L));

        assertThatThrownBy(() -> book(() -> ledger.debit(userId, AccountType.EARNINGS, Money.of("10.00000001"), EntryType.PAYOUT, 2L)))
                .isInstanceOf(InsufficientBalanceException.class);

        assertThat(ledger.balance(userId, AccountType.EARNINGS)).isEqualTo(Money.of("10"));
        assertThat(ledger.latestEntries(userId, 10)).hasSize(1);
        assertThat(ledger.balance(userId, AccountType.CREDIT)).isEqualTo(Money.ZERO);
    }

    @Test
    void concurrentDebitsNeverOverdraw() throws Exception {
        long userId = newUser();
        book(() -> ledger.credit(userId, AccountType.CREDIT, Money.of("5"), EntryType.TOP_UP, 1L));

        List<Callable<Boolean>> debits = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            debits.add(() -> {
                try {
                    book(() -> ledger.debit(userId, AccountType.CREDIT, Money.of("1"), EntryType.USAGE_CHARGE, 2L));
                    return true;
                } catch (InsufficientBalanceException e) {
                    return false;
                }
            });
        }
        int succeeded = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(6)) {
            for (Future<Boolean> result : executor.invokeAll(debits)) {
                succeeded += result.get() ? 1 : 0;
            }
        }

        assertThat(succeeded).isEqualTo(5);
        assertThat(ledger.balance(userId, AccountType.CREDIT)).isEqualTo(Money.ZERO);
        Money sum = ledger.latestEntries(userId, 100).stream().map(e -> e.getAmount()).reduce(Money.ZERO, Money::plus);
        assertThat(sum).isEqualTo(Money.ZERO);
    }

    @Test
    void bookingsRequireATransaction() {
        assertThatThrownBy(() -> ledger.credit(newUser(), AccountType.CREDIT, Money.of("1"), EntryType.TOP_UP, 1L))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    private void book(Runnable booking) {
        transactionTemplate.executeWithoutResult(_ -> booking.run());
    }

    private long newUser() {
        return appUserProvisioning.findOrCreate("ledger-" + UUID.randomUUID(), null, null).getId();
    }
}
