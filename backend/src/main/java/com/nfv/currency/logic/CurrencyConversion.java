package com.nfv.currency.logic;

import com.nfv.common.money.CurrencyAmount;
import com.nfv.common.money.Money;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Result of a conversion into the base currency. Store all fields with every booking (top-up, payout), so that it
 * stays traceable and is never recalculated, see Docs/money.md.
 *
 * @param exchangeRate     units of the original currency per one unit of the base currency, {@code null} if the
 *                         original amount already was in the base currency
 * @param exchangeRateDate date of the exchange rate, {@code null} if not converted
 */
public record CurrencyConversion(CurrencyAmount original, Money base,
                                 @Nullable BigDecimal exchangeRate, @Nullable LocalDate exchangeRateDate) {
}
