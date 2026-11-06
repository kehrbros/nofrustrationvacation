package com.nfv.advisor.logic;

import tools.jackson.databind.JsonNode;

/**
 * One tool of the agent, a new bean is a new tool for the model. The user and the route come from the {@link RunContext},
 * never from the model. No tool changes the trip.
 */
public interface AdvisorTool {
    String name();

    /** When and how to use it, the model reads this. */
    String description();

    /** JSON schema of the arguments in the strict subset, see {@code ConversationTool}. */
    JsonNode parameters();

    boolean appliesTo(RunContext run);

    /**
     * @return answer for the model, everything in it reaches OpenAI
     * @throws com.nfv.assistant.logic.ToolInputException the model can correct the arguments
     */
    JsonNode call(RunContext run, JsonNode arguments);
}
