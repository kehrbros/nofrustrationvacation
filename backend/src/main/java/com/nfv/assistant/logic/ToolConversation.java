package com.nfv.assistant.logic;

import com.nfv.assistant.config.AssistantProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets the model call tools until it finishes with a given last tool (OpenAI Responses API with function calling), see
 * Docs/architecture.md#assistant. Knows no business module: callers bring instructions and tools. Requests are sent
 * with {@code store: false}, so every request repeats all output items, with the encrypted reasoning among them. One
 * tool call per turn, which bounds the calls by {@code maxTurns}. Every turn counts against the limit. Takes as long
 * as the model and the tools do: call it outside of transactions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolConversation {
    private final RestClient openAiRestClient;
    private final AssistantProperties properties;
    private final RateLimiter rateLimiter;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * @param input    what the conversation is about, all the model knows besides the instructions
     * @param finish   the model ends the conversation with this tool, its handler is not called
     * @param maxTurns requests to the model at most; in the last one it has to call {@code finish}
     */
    /**
     * @param parallelToolCalls the model may call several tools in one turn, all are answered before the next
     */
    public record Request(String instructions, String input, List<ConversationTool> tools, ConversationTool finish,
                          int maxTurns, boolean parallelToolCalls) {
        public Request {
            tools = List.copyOf(tools);
            if (maxTurns < 1) {
                throw new IllegalArgumentException("at least one turn");
            }
        }

        public Request(String instructions, String input, List<ConversationTool> tools, ConversationTool finish,
                       int maxTurns) {
            this(instructions, input, tools, finish, maxTurns, false);
        }
    }

    public boolean isAvailable() {
        return properties.isConfigured();
    }

    /**
     * @return the arguments of the call of {@code finish}
     * @throws AssistantUnavailableException no key, the model failed, refused or did not finish
     * @throws AssistantRateLimitedException limit of the user reached, also within the conversation
     */
    public JsonNode run(long appUserId, Request request) {
        return run(request, () -> rateLimiter.acquire(appUserId));
    }

    /** Like {@link #run(long, Request)} for background work, counted against the system quota. */
    public JsonNode runForSystem(Request request) {
        return run(request, rateLimiter::acquireSystem);
    }

    private JsonNode run(Request request, Runnable quota) {
        if (!isAvailable()) {
            throw new AssistantUnavailableException("assistant is not configured");
        }
        ObjectNode body = body(request);
        ArrayNode input = (ArrayNode) body.get("input");
        for (int turn = 1; turn <= request.maxTurns(); turn++) {
            quota.run();
            if (turn == request.maxTurns()) {
                body.putObject("tool_choice").put("type", "function").put("name", request.finish().name());
            }
            JsonNode response = send(body);
            List<JsonNode> calls = calls(response);
            for (JsonNode call : calls) {
                if (request.finish().name().equals(call.path("name").asString(""))) {
                    return arguments(call);
                }
            }
            response.path("output").forEach(input::add);
            calls.forEach(call -> input.addObject().put("type", "function_call_output")
                    .put("call_id", call.path("call_id").asString(""))
                    .put("output", jsonMapper.writeValueAsString(result(call, request.tools()))));
        }
        log.warn("Assistant did not finish within {} turns", request.maxTurns());
        throw new AssistantUnavailableException("assistant did not finish");
    }

    private ObjectNode body(Request request) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("model", properties.model());
        body.put("store", false);
        body.put("instructions", request.instructions());
        body.put("parallel_tool_calls", request.parallelToolCalls());
        body.put("tool_choice", "required");
        ArrayNode tools = body.putArray("tools");
        request.tools().forEach(tool -> addTool(tools, tool));
        addTool(tools, request.finish());
        body.putArray("input").addObject().put("role", "user").put("content", request.input());
        return body;
    }

    private static void addTool(ArrayNode tools, ConversationTool tool) {
        tools.addObject().put("type", "function").put("name", tool.name()).put("description", tool.description())
                .put("strict", true).set("parameters", tool.parameters());
    }

    /**
     * What a tool answers. The model can react to unknown tools and {@link ToolInputException}, every other exception
     * ends the conversation and goes to the caller.
     */
    private JsonNode result(JsonNode call, List<ConversationTool> tools) {
        String name = call.path("name").asString("");
        ConversationTool tool = tools.stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        if (tool == null) {
            return error("unknown tool " + name);
        }
        try {
            return tool.handler().apply(arguments(call));
        } catch (ToolInputException e) {
            return error(e.getMessage());
        }
    }

    private JsonNode error(String message) {
        return jsonMapper.createObjectNode().put("error", message);
    }

    /** The arguments are a JSON string; strict tools make them valid against the schema. */
    private JsonNode arguments(JsonNode call) {
        try {
            return jsonMapper.readTree(call.path("arguments").asString(""));
        } catch (JacksonException e) {
            log.warn("Assistant called a tool with invalid arguments: {}", e.getOriginalMessage());
            throw new AssistantUnavailableException("assistant answer unusable");
        }
    }

    /** The tool calls of a complete answer. A refusal, an incomplete answer or one without a call counts as failure. */
    private List<JsonNode> calls(JsonNode response) {
        if (!"completed".equals(response.path("status").asString(""))) {
            log.warn("Assistant answered with status {}", response.path("status").asString("?"));
            throw new AssistantUnavailableException("assistant answer unusable");
        }
        List<JsonNode> calls = new ArrayList<>();
        for (JsonNode item : response.path("output")) {
            if ("function_call".equals(item.path("type").asString(""))) {
                calls.add(item);
            }
        }
        if (calls.isEmpty()) {
            log.warn("Assistant answered without a tool call");
            throw new AssistantUnavailableException("assistant answer unusable");
        }
        return calls;
    }

    private JsonNode send(ObjectNode body) {
        try {
            String answer = openAiRestClient.post().uri("/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(body))
                    .retrieve()
                    .body(String.class);
            return jsonMapper.readTree(answer == null ? "" : answer);
        } catch (RestClientException e) {
            log.warn("Assistant request failed: {}", e.getMessage());
            throw new AssistantUnavailableException("assistant request failed");
        } catch (JacksonException e) {
            log.warn("Assistant answer is not JSON: {}", e.getOriginalMessage());
            throw new AssistantUnavailableException("assistant answer unusable");
        }
    }
}
