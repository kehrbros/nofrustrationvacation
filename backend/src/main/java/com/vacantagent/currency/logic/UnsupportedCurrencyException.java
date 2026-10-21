package com.vacantagent.currency.logic;

import com.vacantagent.common.errorhandling.BusinessException;

public class UnsupportedCurrencyException extends BusinessException {
    public UnsupportedCurrencyException(String currencyCode) {
        super("Unsupported currency: " + currencyCode);
    }
}
