package com.nfv.currency.logic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;

/**
 * Exchange rate delivered by an {@link ExchangeRateProvider}: {@code rate} units of {@code currency} per one unit of
 * the base currency.
 */
public record ExchangeRateQuote(Currency currency, BigDecimal rate, LocalDate rateDate) {
}
