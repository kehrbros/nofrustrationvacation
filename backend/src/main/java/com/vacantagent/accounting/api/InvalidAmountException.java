package com.vacantagent.accounting.api;

import com.vacantagent.accounting.logic.AccountingException;

/** Amount missing, not positive, or with more decimal places than its currency has. */
public class InvalidAmountException extends AccountingException {
    public InvalidAmountException(String message) {
        super("INVALID_AMOUNT", message);
    }
}
