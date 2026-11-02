package com.nfv.currency.logic;

import com.nfv.common.errorhandling.BusinessException;

public class UnsupportedCurrencyException extends BusinessException {
    public UnsupportedCurrencyException(String currencyCode) {
        super("Unsupported currency: " + currencyCode);
    }
}
