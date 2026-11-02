package com.nfv.accounting.entities;

import com.nfv.common.data.AbstractEntity;
import com.nfv.common.money.Money;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * One booking on an {@link Account}. Append-only: entries are never changed or deleted, a correction is a new entry.
 * The sum of all entries of an account equals its balance.
 */
@Getter
@Entity
public class LedgerEntry extends AbstractEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Account account;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private EntryType type;
    /** Positive for credits, negative for debits. */
    @Column(nullable = false, precision = 19, scale = Money.SCALE)
    private Money amount;
    private @Nullable Long referenceId;
    @Column(nullable = false)
    private OffsetDateTime bookedAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(Account account, EntryType type, Money amount, @Nullable Long referenceId, OffsetDateTime bookedAt) {
        this.account = account;
        this.type = type;
        this.amount = amount;
        this.referenceId = referenceId;
        this.bookedAt = bookedAt;
    }
}
