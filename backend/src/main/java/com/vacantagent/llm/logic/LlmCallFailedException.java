package com.vacantagent.llm.logic;

import com.vacantagent.common.errorhandling.TechnicalException;

/** The provider's agent did not complete the prompt; nothing was charged. */
public class LlmCallFailedException extends TechnicalException {
    public LlmCallFailedException(long agentId, Throwable cause) {
        super("Agent " + agentId + " did not complete the prompt");
        initCause(cause);
    }
}