package com.nfv.currency.logic;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Rounding of amounts converted to the currency of an API response, see Docs/money.md.
 */
public enum MoneyRounding {
    /** Prices per million tokens and costs of single requests: 6 decimal places, so small prices stay visible. */
    PRICE {
        @Override
        public BigDecimal apply(BigDecimal amount, Currency currency) {
            return amount.setScale(6, RoundingMode.HALF_EVEN);
        }
    },
    /** Balances, invoice and payment amounts: minor units of the currency (2 for EUR, 0 for JPY). */
    AMOUNT {
        @Override
        public BigDecimal apply(BigDecimal amount, Currency currency) {
            return amount.setScale(fractionDigits(currency), RoundingMode.HALF_EVEN);
        }
    },
    /** Payouts: minor units of the currency, rounded down so that never more than the balance is paid out. */
    PAYOUT {
        @Override
        public BigDecimal apply(BigDecimal amount, Currency currency) {
            return amount.setScale(fractionDigits(currency), RoundingMode.DOWN);
        }
    };

    public abstract BigDecimal apply(BigDecimal amount, Currency currency);

    private static int fractionDigits(Currency currency) {
        return Math.max(currency.getDefaultFractionDigits(), 0);
    }
}
