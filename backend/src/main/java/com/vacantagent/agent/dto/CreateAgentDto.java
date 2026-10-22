package com.vacantagent.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

/** @param baseUrl https endpoint of an OpenAI-compatible API, empty for the vendor's default */
public record CreateAgentDto(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Mein OpenAI-Zugang") String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "openai") String modelProvider,
        @Nullable String baseUrl,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "password") String apiKey) {
}
