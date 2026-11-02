package com.vacantagent.assistant.logic;

import com.vacantagent.common.errorhandling.BusinessException;

/** The user made too many requests to the model within the last hour. */
public class AssistantRateLimitedException extends BusinessException {
    public AssistantRateLimitedException() {
        super("too many assistant requests, try again later");
    }
}
