package com.nfv.common.errorhandling;

public class TechnicalException extends NfvException {
    public TechnicalException() {
    }

    public TechnicalException(String message) {
        super(message);
    }
}
