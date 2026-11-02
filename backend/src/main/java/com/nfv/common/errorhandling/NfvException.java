package com.nfv.common.errorhandling;

public class NfvException extends RuntimeException {
    public NfvException() {
    }

    public NfvException(String message) {
        super(message);
    }
}
