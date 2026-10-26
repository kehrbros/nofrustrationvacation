package com.vacantagent.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

/**
 * @param name    model sold with the key; offers of the agent sell this model
 * @param baseUrl https endpoint of an OpenAI-compatible API, empty for the vendor's default
 */
public record CreateAgentDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "gpt-5") String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "openai") String modelProvider,
        @Nullable String baseUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "password") String apiKey) {
}