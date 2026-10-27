package com.vacantagent.agent.logic;

import com.vacantagent.common.errorhandling.BusinessException;

/** The agent still serves offers of the market and cannot be removed. */
public class AgentInUseException extends BusinessException {
    public AgentInUseException(long agentId) {
        super("Agent " + agentId + " is used by offers");
    }
}