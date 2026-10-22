package com.vacantagent.agent.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ApiKeyDto(@Schema(requiredMode = Schema.RequiredMode.REQUIRED, format = "password") String apiKey) {
}
