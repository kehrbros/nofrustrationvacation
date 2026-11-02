package com.nfv.accounting.entities;

import com.nfv.common.data.AbstractEntity;
import com.nfv.common.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * Balance of one user and {@link AccountType}. The balance only changes together with a {@link LedgerEntry}, through
 * the atomic updates of {@code AccountRepository}, never through this entity.
 */
@Getter
@Entity
@Table(name = "accounting_account")
public class Account extends AbstractEntity {
    @Column(nullable = false)
    private long appUserId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountType type;
    @Column(nullable = false, precision = 19, scale = Money.SCALE)
    private Money balance;
    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected Account() {
    }

    public Account(long appUserId, AccountType type, OffsetDateTime createdAt) {
        this.appUserId = appUserId;
        this.type = type;
        this.balance = Money.ZERO;
        this.createdAt = createdAt;
    }
}
