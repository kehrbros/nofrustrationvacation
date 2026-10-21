package com.vacantagent.llm.api;

import org.springframework.http.HttpRequest;

public record LlmCall(long userId, HttpRequest request) {
}
