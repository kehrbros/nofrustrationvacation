package com.nfv.assistant.logic;

import tools.jackson.databind.JsonNode;

import java.util.function.Function;

/**
 * A tool the model may call in a {@link ToolConversation}.
 *
 * @param name        short name, letters, digits, underscores
 * @param description when and how to use it, the model reads this
 * @param parameters  JSON schema of the arguments in the strict subset (all properties required, optional ones with
 *                    {@code null} as a type, {@code additionalProperties: false})
 * @param handler     arguments → answer for the model; {@link ToolInputException} goes back to the model as an error,
 *                    any other exception ends the conversation. Everything it answers reaches the model.
 */
public record ConversationTool(String name, String description, JsonNode parameters,
                               Function<JsonNode, JsonNode> handler) {
}
