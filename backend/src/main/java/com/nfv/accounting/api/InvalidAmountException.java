package com.nfv.accounting.api;

import com.nfv.accounting.logic.AccountingException;

/** Amount missing, not positive, or with more decimal places than its currency has. */
public class InvalidAmountException extends AccountingException {
    public InvalidAmountException(String message) {
        super("INVALID_AMOUNT", message);
    }
}
