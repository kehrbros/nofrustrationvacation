package com.nfv.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Access to the language model (OpenAI), see Docs/architecture.md#assistant.
 *
 * @param apiKey          OpenAI API key, empty: the assistant is switched off and callers fall back to manual input
 * @param baseUrl         API base, e.g. {@code https://api.openai.com/v1}
 * @param model           model for extractions, must support structured outputs and image/PDF input
 * @param timeout         per request to the model
 * @param requestsPerHour per user, protects against runaway costs
 */
@ConfigurationProperties("nfv.assistant")
public record AssistantProperties(String apiKey, String baseUrl, String model, Duration timeout, int requestsPerHour) {

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
