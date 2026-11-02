package com.nfv.accounting.logic;

public class PayoutAccountRequiredException extends AccountingException {
    public PayoutAccountRequiredException() {
        super("PAYOUT_ACCOUNT_REQUIRED", "Link a PayPal account first");
    }
}
