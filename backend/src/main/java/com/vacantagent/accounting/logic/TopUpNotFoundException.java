package com.vacantagent.accounting.logic;

public class TopUpNotFoundException extends AccountingException {
    public TopUpNotFoundException(String paypalOrderId) {
        super("TOP_UP_NOT_FOUND", "No top-up for PayPal order " + paypalOrderId);
    }
}
