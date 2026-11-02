package com.nfv.paypal;

/**
 * PayPal could not be reached or answered with a 5xx status. The outcome of the request is unknown: it may have
 * been executed. Only repeat requests that are idempotent (same {@code PayPal-Request-Id} or sender batch id).
 */
public class PayPalUnavailableException extends PayPalException {
    public PayPalUnavailableException(String message, Throwable cause) {
        super(message);
        initCause(cause);
    }

    public PayPalUnavailableException(String message) {
        super(message);
    }
}
