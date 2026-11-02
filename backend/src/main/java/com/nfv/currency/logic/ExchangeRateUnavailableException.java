package com.nfv.currency.logic;

import com.nfv.common.errorhandling.BusinessException;

/**
 * No exchange rate is known for a currency, or the latest one is too old for a conversion with booking effect.
 */
public class ExchangeRateUnavailableException extends BusinessException {
    public ExchangeRateUnavailableException(String message) {
        super(message);
    }
}
