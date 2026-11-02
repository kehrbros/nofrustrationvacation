package com.nfv.assistant.logic;

import com.nfv.common.errorhandling.BusinessException;

/** The user made too many requests to the model within the last hour. */
public class AssistantRateLimitedException extends BusinessException {
    public AssistantRateLimitedException() {
        super("too many assistant requests, try again later");
    }
}
