package com.vacantagent.common.errorhandling;

public class VacantAgentException extends RuntimeException {
    public VacantAgentException() {
    }

    public VacantAgentException(String message) {
        super(message);
    }
}
