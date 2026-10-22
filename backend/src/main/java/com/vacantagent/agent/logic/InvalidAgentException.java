package com.vacantagent.agent.logic;

import com.vacantagent.common.errorhandling.BusinessException;

/** Name, model provider, base URL or API key missing or invalid. */
public class InvalidAgentException extends BusinessException {
    public InvalidAgentException(String message) {
        super(message);
    }
}
