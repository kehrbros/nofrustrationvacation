package com.vacantagent.accounting.logic;

import com.vacantagent.accounting.data.AccountRepository;
import com.vacantagent.accounting.data.LedgerEntryRepository;
import com.vacantagent.accounting.entities.Account;
import com.vacantagent.accounting.entities.AccountType;
import com.vacantagent.accounting.entities.EntryType;
import com.vacantagent.accounting.entities.LedgerEntry;
import com.vacantagent.common.money.Money;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Books on the accounts of a user. Every balance change writes a {@link LedgerEntry} in the same transaction;
 * the balance itself is changed with one atomic, guarded update, so it never goes below zero and concurrent
 * bookings need no locks, see Docs/accounting.md.
 */
@Service
@RequiredArgsConstructor
public class AccountLedger {
    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountCreator accountCreator;
    private final Clock clock;

    /** Books a positive amount onto the account. Must run inside the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void credit(long appUserId, AccountType type, Money amount, EntryType entryType, @Nullable Long referenceId) {
        requirePositive(amount);
        Account account = account(appUserId, type);
        accountRepository.addToBalance(account.getId(), amount.amount());
        ledgerEntryRepository.save(new LedgerEntry(account, entryType, amount, referenceId, OffsetDateTime.now(clock)));
    }

    /**
     * Books a positive amount off the account. Must run inside the caller's transaction.
     *
     * @throws InsufficientBalanceException if the balance does not cover the amount, nothing is booked then
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void debit(long appUserId, AccountType type, Money amount, EntryType entryType, @Nullable Long referenceId) {
        requirePositive(amount);
        Account account = account(appUserId, type);
        if (accountRepository.addToBalance(account.getId(), amount.amount().negate()) == 0) {
            throw new InsufficientBalanceException("Balance of " + type + " account does not cover " + amount);
        }
        ledgerEntryRepository.save(new LedgerEntry(account, entryType, Money.ZERO.minus(amount), referenceId,
                OffsetDateTime.now(clock)));
    }

    /**
     * Like {@link #debit}, but the balance may go below zero, for usage that was already delivered. Only
     * {@link AccountType#CREDIT} accounts may go below zero. Must run inside the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void debitAllowingNegative(long appUserId, AccountType type, Money amount, EntryType entryType,
                                      @Nullable Long referenceId) {
        if (type != AccountType.CREDIT) throw new IllegalArgumentException("Only CREDIT may go below zero: " + type);
        requirePositive(amount);
        Account account = account(appUserId, type);
        accountRepository.addToBalanceAllowingNegative(account.getId(), amount.amount().negate());
        ledgerEntryRepository.save(new LedgerEntry(account, entryType, Money.ZERO.minus(amount), referenceId,
                OffsetDateTime.now(clock)));
    }

    @Transactional(readOnly = true)
    public Money balance(long appUserId, AccountType type) {
        return accountRepository.findByAppUserIdAndType(appUserId, type).map(Account::getBalance).orElse(Money.ZERO);
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> latestEntries(long appUserId, int limit) {
        return ledgerEntryRepository.findByAccountAppUserIdOrderByBookedAtDescIdDesc(appUserId, PageRequest.of(0, limit));
    }

    private Account account(long appUserId, AccountType type) {
        return accountRepository.findByAppUserIdAndType(appUserId, type).orElseGet(() -> {
            try {
                return accountCreator.create(appUserId, type);
            } catch (DataIntegrityViolationException e) {
                // created concurrently by another booking
                return accountRepository.findByAppUserIdAndType(appUserId, type).orElseThrow(() -> e);
            }
        });
    }

    private static void requirePositive(Money amount) {
        if (amount.isNegative() || amount.isZero()) {
            throw new IllegalArgumentException("Booking amount must be positive: " + amount);
        }
    }
}
