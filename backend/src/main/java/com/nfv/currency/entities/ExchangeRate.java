package com.nfv.currency.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Daily exchange rate: {@link #rate} units of {@link #currency} per one unit of the base currency.
 * Not audited, one row per currency and day is the history, see Docs/money.md.
 */
@Getter
@Entity
public class ExchangeRate extends AbstractEntity {
    public static final int RATE_SCALE = 10;

    @Column(nullable = false, length = 3)
    private String currency;
    @Column(nullable = false, precision = 19, scale = RATE_SCALE)
    private BigDecimal rate;
    @Column(nullable = false)
    private LocalDate rateDate;
    @Column(nullable = false)
    private OffsetDateTime fetchedAt;
    @Column(nullable = false)
    private String source;

    protected ExchangeRate() {
    }

    public ExchangeRate(String currency, BigDecimal rate, LocalDate rateDate, OffsetDateTime fetchedAt, String source) {
        this.currency = currency;
        this.rate = rate;
        this.rateDate = rateDate;
        this.fetchedAt = fetchedAt;
        this.source = source;
    }
}
