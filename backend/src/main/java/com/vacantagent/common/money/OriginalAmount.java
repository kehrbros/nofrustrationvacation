package com.vacantagent.common.money;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Amount of a booking in the currency it was actually paid in (e.g. a PayPal payment in EUR), together with the
 * exchange rate used for the base amount. Stored with the booking for traceability, never used for calculations
 * and never recalculated, see Docs/money.md.
 *
 * @param exchangeRate     units of {@code currency} per one unit of the base currency, {@code null} if the
 *                         booking was in the base currency
 * @param exchangeRateDate date of the exchange rate, {@code null} if not converted
 */
@Embeddable
public record OriginalAmount(
        @Column(name = "original_amount", precision = 19, scale = Money.SCALE) BigDecimal amount,
        @Column(name = "original_currency", length = 3) String currency,
        @Column(name = "exchange_rate", precision = 19, scale = 10) @Nullable BigDecimal exchangeRate,
        @Column(name = "exchange_rate_date") @Nullable LocalDate exchangeRateDate) {

    public OriginalAmount {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    public static OriginalAmount inBaseCurrency(Money money) {
        return new OriginalAmount(money.amount(), Money.BASE_CURRENCY.getCurrencyCode(), null, null);
    }
}
