package com.nfv.currency.logic;

import java.util.List;

/**
 * Source of exchange rates relative to the base currency. Replaceable, e.g. by a paid provider with hourly rates.
 */
public interface ExchangeRateProvider {
    String source();

    List<ExchangeRateQuote> fetchLatest();
}
