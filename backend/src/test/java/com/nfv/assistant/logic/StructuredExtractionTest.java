package com.nfv.assistant.logic;

import com.nfv.assistant.config.AssistantProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Requests of {@link StructuredExtraction} against a mocked OpenAI server. */
class StructuredExtractionTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode SCHEMA = JSON.readTree("""
            {"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"],"additionalProperties":false}""");
    private static final String ANSWER = """
            {"status":"completed","output":[{"type":"message","content":[
              {"type":"output_text","text":"{\\"ok\\":true}"}]}]}""";

    private final List<JsonNode> requests = new ArrayList<>();
    private MockRestServiceServer server;
    private StructuredExtraction extraction;

    @BeforeEach
    void setUp() {
        AssistantProperties properties = new AssistantProperties("key", "https://openai.test/v1", "test-model", "image",
                "low", Duration.ofSeconds(1), 30, 60);
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        extraction = new StructuredExtraction(builder.build(), properties, new RateLimiter(properties, Clock.systemUTC()));
    }

    @Test
    void reasoningEffortIsSentOnlyWhenGiven() {
        expect();
        expect();

        JsonNode fast = extraction.extract(7, "Check", "check", SCHEMA, List.of(new ExtractionInput.Text("x")),
                ReasoningEffort.LOW);
        extraction.extract(7, "Check", "check", SCHEMA, List.of(new ExtractionInput.Text("x")));

        server.verify();
        assertThat(fast.path("ok").asBoolean()).isTrue();
        assertThat(requests.get(0).path("reasoning").path("effort").asString()).isEqualTo("low");
        assertThat(requests.get(0).has("tools")).isFalse();
        assertThat(requests.get(1).has("reasoning")).isFalse();
    }

    private void expect() {
        server.expect(requestTo("https://openai.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> requests.add(JSON.readTree(((MockClientHttpRequest) request).getBodyAsString())))
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));
    }
}
