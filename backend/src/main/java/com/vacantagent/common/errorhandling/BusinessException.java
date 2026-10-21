package com.vacantagent.common.errorhandling;

public class BusinessException extends VacantAgentException {
    public BusinessException() {
    }

    public BusinessException(String message) {
        super(message);
    }
}
