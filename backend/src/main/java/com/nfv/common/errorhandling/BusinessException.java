package com.nfv.common.errorhandling;

public class BusinessException extends NfvException {
    public BusinessException() {
    }

    public BusinessException(String message) {
        super(message);
    }
}
