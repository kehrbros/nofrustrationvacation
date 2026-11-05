package com.nfv.bookings.logic;

import com.nfv.common.errorhandling.TechnicalException;

/** LiteAPI is not configured, not reachable or answered with an error; the outcome of a booking call is unknown. */
public class LiteApiUnavailableException extends TechnicalException {
    public LiteApiUnavailableException(String message) {
        super(message);
    }
}
