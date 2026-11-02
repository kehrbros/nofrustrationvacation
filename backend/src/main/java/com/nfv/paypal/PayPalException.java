package com.nfv.paypal;

import com.nfv.common.errorhandling.TechnicalException;

/** Base of all errors of the PayPal module. */
public abstract class PayPalException extends TechnicalException {
    protected PayPalException(String message) {
        super(message);
    }
}
