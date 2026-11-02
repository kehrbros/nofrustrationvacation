package com.nfv.accounting.logic;

public class AmountOutOfRangeException extends AccountingException {
    public AmountOutOfRangeException(String message) {
        super("AMOUNT_OUT_OF_RANGE", message);
    }
}
