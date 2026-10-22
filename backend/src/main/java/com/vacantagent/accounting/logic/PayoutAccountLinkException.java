package com.vacantagent.accounting.logic;

/** The PayPal link could not be completed, see the code. */
public class PayoutAccountLinkException extends AccountingException {
    public static final String INVALID_STATE = "LINK_STATE_INVALID";
    public static final String ACCOUNT_IN_USE = "PAYPAL_ACCOUNT_IN_USE";
    public static final String PAYOUTS_OPEN = "PAYOUTS_OPEN";

    public PayoutAccountLinkException(String code, String message) {
        super(code, message);
    }
}
