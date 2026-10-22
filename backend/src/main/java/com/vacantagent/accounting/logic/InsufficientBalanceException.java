package com.vacantagent.accounting.logic;

public class InsufficientBalanceException extends AccountingException {
    public InsufficientBalanceException(String message) {
        super("INSUFFICIENT_BALANCE", message);
    }
}
