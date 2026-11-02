package com.nfv.common.money;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

/**
 * Amount in any currency. Only used at the API boundary and for currency conversion, never in logic or persistence,
 * see Docs/money.md.
 */
public record CurrencyAmount(BigDecimal amount, Currency currency) {
    public CurrencyAmount {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
    }

    public static CurrencyAmount of(String amount, String currencyCode) {
        return new CurrencyAmount(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }
}
