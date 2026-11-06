package com.nfv.assistant.logic;

import com.nfv.assistant.config.AssistantProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ToolConversationTest {
    private static final long USER = 7;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode SCHEMA = JSON.readTree("""
            {"type":"object","properties":{},"required":[],"additionalProperties":false}""");
    private static final ConversationTool FINISH = new ConversationTool("finish", "Ends it", SCHEMA, _ -> {
        throw new AssertionError("the handler of the last tool is not called");
    });
    private static final String REASONING = """
            {"id":"rs_1","type":"reasoning","encrypted_content":"secret","summary":[]}""";

    private final List<JsonNode> requests = new ArrayList<>();
    private final List<JsonNode> toolArguments = new ArrayList<>();
    private MockRestServiceServer server;
    private ToolConversation conversation;

    @BeforeEach
    void setUp() {
        conversation = conversation("key", 30, 60);
    }

    @Test
    void repeatsAllOutputItemsWithTheAnswerOfTheTool() {
        expect(answer(REASONING, call("c1", "lookup", "{\"place\":\"Taupo\"}")));
        expect(answer(call("c2", "finish", "{\"pick\":\"a\"}")));

        JsonNode result = conversation.run(USER, request(answerWith("found"), 5));

        server.verify();
        assertThat(result.path("pick").asString()).isEqualTo("a");
        assertThat(toolArguments.getFirst().path("place").asString()).isEqualTo("Taupo");
        JsonNode first = requests.getFirst();
        assertThat(first.path("model").asString()).isEqualTo("test-model");
        assertThat(first.path("store").asBoolean(true)).isFalse();
        assertThat(first.path("parallel_tool_calls").asBoolean(true)).isFalse();
        assertThat(first.path("tool_choice").asString()).isEqualTo("required");
        assertThat(first.path("instructions").asString()).isEqualTo("Help");
        assertThat(first.path("input")).hasSize(1);
        assertThat(first.path("input").get(0).path("content").asString()).isEqualTo("The route");
        assertThat(first.path("tools")).extracting(tool -> tool.path("name").asString())
                .containsExactly("lookup", "finish");
        assertThat(first.path("tools")).allSatisfy(tool -> {
            assertThat(tool.path("type").asString()).isEqualTo("function");
            assertThat(tool.path("strict").asBoolean()).isTrue();
            assertThat(tool.path("parameters")).isEqualTo(SCHEMA);
        });
        JsonNode input = requests.get(1).path("input");
        assertThat(input).hasSize(4);
        assertThat(input.get(1)).as("reasoning repeated unchanged").isEqualTo(JSON.readTree(REASONING));
        assertThat(input.get(2).path("type").asString()).isEqualTo("function_call");
        assertThat(input.get(3).path("type").asString()).isEqualTo("function_call_output");
        assertThat(input.get(3).path("call_id").asString()).isEqualTo("c1");
        assertThat(JSON.readTree(input.get(3).path("output").asString()).path("value").asString()).isEqualTo("found");
    }

    @Test
    void lastTurnHasToFinish() {
        expect(answer(call("c1", "lookup", "{}")));
        expect(answer(call("c2", "finish", "{}")));

        conversation.run(USER, request(answerWith("found"), 2));

        assertThat(requests.getFirst().path("tool_choice").asString()).isEqualTo("required");
        JsonNode forced = requests.get(1).path("tool_choice");
        assertThat(forced.path("type").asString()).isEqualTo("function");
        assertThat(forced.path("name").asString()).isEqualTo("finish");
    }

    @Test
    void notFinishingInTheLastTurnFails() {
        expect(answer(call("c1", "lookup", "{}")));

        assertThatThrownBy(() -> conversation.run(USER, request(answerWith("found"), 1)))
                .isInstanceOf(AssistantUnavailableException.class);
    }

    @Test
    void toolInputErrorGoesBackToTheModel() {
        expect(answer(call("c1", "lookup", "{}")));
        expect(answer(call("c2", "finish", "{}")));

        conversation.run(USER, request(_ -> {
            throw new ToolInputException("count out of range");
        }, 5));

        assertThat(lastOutput().path("error").asString()).isEqualTo("count out of range");
    }

    @Test
    void unknownToolGoesBackToTheModel() {
        expect(answer(call("c1", "invented", "{}")));
        expect(answer(call("c2", "finish", "{}")));

        conversation.run(USER, request(answerWith("found"), 5));

        assertThat(lastOutput().path("error").asString()).isEqualTo("unknown tool invented");
        assertThat(toolArguments).isEmpty();
    }

    @Test
    void otherExceptionOfAToolEndsTheConversation() {
        expect(answer(call("c1", "lookup", "{}")));

        assertThatThrownBy(() -> conversation.run(USER, request(_ -> {
            throw new IllegalStateException("no graph");
        }, 5))).isInstanceOf(IllegalStateException.class).hasMessage("no graph");
    }

    @Test
    void finishWinsOverOtherCallsOfTheSameAnswer() {
        expect(answer(call("c1", "lookup", "{}"), call("c2", "finish", "{\"pick\":\"a\"}")));

        assertThat(conversation.run(USER, request(answerWith("found"), 5)).path("pick").asString()).isEqualTo("a");
        assertThat(toolArguments).isEmpty();
    }

    @Test
    void answersWithoutACallFail() {
        for (String answer : List.of(answer("""
                        {"type":"message","content":[{"type":"output_text","text":"Sorry"}]}"""),
                answer("""
                        {"type":"message","content":[{"type":"refusal","refusal":"No"}]}"""),
                """
                        {"status":"incomplete","output":[%s]}""".formatted(call("c1", "finish", "{")),
                """
                        {"status":"failed","output":[]}""",
                "not json")) {
            conversation = conversation("key", 30, 60);
            expect(answer);

            assertThatThrownBy(() -> conversation.run(USER, request(answerWith("found"), 5)))
                    .as(answer).isInstanceOf(AssistantUnavailableException.class);
        }
    }

    @Test
    void invalidArgumentsFail() {
        expect(answer(call("c1", "finish", "{")));

        assertThatThrownBy(() -> conversation.run(USER, request(answerWith("found"), 5)))
                .isInstanceOf(AssistantUnavailableException.class);
    }

    @Test
    void errorStatusFails() {
        server.expect(requestTo("https://openai.test/v1/responses")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> conversation.run(USER, request(answerWith("found"), 5)))
                .isInstanceOf(AssistantUnavailableException.class);
    }

    @Test
    void withoutKeyNothingIsSent() {
        conversation = conversation(" ", 30, 60);

        assertThat(conversation.isAvailable()).isFalse();
        assertThatThrownBy(() -> conversation.run(USER, request(answerWith("found"), 5)))
                .isInstanceOf(AssistantUnavailableException.class);
        server.verify();
    }

    @Test
    void everyTurnCountsForTheUser() {
        conversation = conversation("key", 1, 60);
        expect(answer(call("c1", "lookup", "{}")));

        assertThatThrownBy(() -> conversation.run(USER, request(answerWith("found"), 5)))
                .isInstanceOf(AssistantRateLimitedException.class);
        assertThat(requests).hasSize(1);
    }

    @Test
    void everyTurnCountsForTheSystemQuota() {
        conversation = conversation("key", 30, 1);
        expect(answer(call("c1", "lookup", "{}")));

        assertThatThrownBy(() -> conversation.runForSystem(request(answerWith("found"), 5)))
                .isInstanceOf(AssistantRateLimitedException.class);
        assertThat(requests).hasSize(1);
    }

    private ToolConversation conversation(String key, int perUser, int perSystem) {
        AssistantProperties properties = new AssistantProperties(key, "https://openai.test/v1", "test-model", "image",
                "low", Duration.ofSeconds(1), perUser, perSystem);
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl());
        server = MockRestServiceServer.bindTo(builder).build();
        return new ToolConversation(builder.build(), properties, new RateLimiter(properties, Clock.systemUTC()));
    }

    private ToolConversation.Request request(Function<JsonNode, JsonNode> handler, int maxTurns) {
        ConversationTool lookup = new ConversationTool("lookup", "Looks up", SCHEMA, arguments -> {
            toolArguments.add(arguments);
            return handler.apply(arguments);
        });
        return new ToolConversation.Request("Help", "The route", List.of(lookup), FINISH, maxTurns);
    }

    private static Function<JsonNode, JsonNode> answerWith(String value) {
        return _ -> JSON.createObjectNode().put("value", value);
    }

    private void expect(String answer) {
        server.expect(requestTo("https://openai.test/v1/responses"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(request -> requests.add(JSON.readTree(((MockClientHttpRequest) request).getBodyAsString())))
                .andRespond(withSuccess(answer, MediaType.APPLICATION_JSON));
    }

    private JsonNode lastOutput() {
        JsonNode input = requests.getLast().path("input");
        return JSON.readTree(input.get(input.size() - 1).path("output").asString());
    }

    private static String answer(String... items) {
        return "{\"status\":\"completed\",\"output\":[" + String.join(",", items) + "]}";
    }

    private static String call(String callId, String name, String arguments) {
        return JSON.writeValueAsString(JSON.createObjectNode().put("id", "fc_" + callId).put("type", "function_call")
                .put("call_id", callId).put("name", name).put("arguments", arguments));
    }
}
