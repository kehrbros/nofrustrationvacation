package com.nfv.assistant.logic;

import com.nfv.common.errorhandling.BusinessException;

/** No API key, the model failed or answered unusably. Callers fall back to manual input. */
public class AssistantUnavailableException extends BusinessException {
    public AssistantUnavailableException(String message) {
        super(message);
    }
}
