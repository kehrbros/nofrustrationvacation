package com.vacantagent.accounting.logic;

import com.vacantagent.accounting.data.AccountRepository;
import com.vacantagent.accounting.entities.Account;
import com.vacantagent.accounting.entities.AccountType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Separate bean so that {@code REQUIRES_NEW} applies via the transaction proxy: a concurrent insert of the same
 * account fails on the unique constraint without spoiling the caller's transaction (as {@code AppUserCreator}).
 */
@Component
@RequiredArgsConstructor
class AccountCreator {
    private final AccountRepository accountRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Account create(long appUserId, AccountType type) {
        return accountRepository.saveAndFlush(new Account(appUserId, type, OffsetDateTime.now(clock)));
    }
}
