package com.vacantagent.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

/**
 * @param name       model sold with the key
 * @param apiKeyHint last characters of the API key, the key itself is never returned
 */
public record AgentDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "gpt-5") String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "openai") String modelProvider,
        @Nullable String baseUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "x7Qa") String apiKeyHint,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime createdAt) {
}