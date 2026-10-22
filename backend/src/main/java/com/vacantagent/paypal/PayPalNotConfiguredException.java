package com.vacantagent.paypal;

/** {@code PAYPAL_CLIENT_ID} or {@code PAYPAL_CLIENT_SECRET} is missing, see Docs/paypal.md. */
public class PayPalNotConfiguredException extends PayPalException {
    public PayPalNotConfiguredException() {
        super("PayPal client id and secret are not configured");
    }
}
