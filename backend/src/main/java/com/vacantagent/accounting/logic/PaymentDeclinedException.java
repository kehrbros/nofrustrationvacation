package com.vacantagent.accounting.logic;

/** PayPal refused the payment or payout, e.g. not approved or declined funding source. */
public class PaymentDeclinedException extends AccountingException {
    public PaymentDeclinedException(String code, String message) {
        super(code, message);
    }
}
