package com.vacantagent.assistant.logic;

import com.vacantagent.common.errorhandling.BusinessException;

/** No API key, the model failed or answered unusably. Callers fall back to manual input. */
public class AssistantUnavailableException extends BusinessException {
    public AssistantUnavailableException(String message) {
        super(message);
    }
}
