package com.vacantagent.llm.logic;

import com.vacantagent.common.errorhandling.BusinessException;

/** The prompt cannot be sent: missing, no positive credit or no active agreement. */
public class LlmRequestException extends BusinessException {
    public LlmRequestException(String message) {
        super(message);
    }
}