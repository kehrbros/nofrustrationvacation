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

import java.util.Base64;
import java.util.List;

/**
 * Lets the model read text, images or PDFs and answer as JSON of a given schema (OpenAI Responses API with
 * structured outputs). Knows no business module: callers bring instructions and schema. Requests are sent with
 * {@code store: false}, inputs are not kept here either.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StructuredExtraction {
    private final RestClient openAiRestClient;
    private final AssistantProperties properties;
    private final RateLimiter rateLimiter;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public boolean isAvailable() {
        return properties.isConfigured();
    }

    /**
     * @param schemaName short name of the schema, letters, digits, underscores
     * @param schema     JSON schema in the strict subset of structured outputs (all properties required,
     *                   {@code additionalProperties: false})
     * @return the answer, valid against the schema
     */
    public JsonNode extract(long appUserId, String instructions, String schemaName, JsonNode schema,
                            List<ExtractionInput> inputs) {
        if (!isAvailable()) {
            throw new AssistantUnavailableException("assistant is not configured");
        }
        rateLimiter.acquire(appUserId);
        ObjectNode request = request(instructions, schemaName, schema, inputs);
        String body;
        try {
            body = openAiRestClient.post().uri("/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(request))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            log.warn("Assistant request failed: {}", e.getMessage());
            throw new AssistantUnavailableException("assistant request failed");
        }
        return answer(body);
    }

    private ObjectNode request(String instructions, String schemaName, JsonNode schema, List<ExtractionInput> inputs) {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("model", properties.model());
        request.put("store", false);
        request.put("instructions", instructions);
        ArrayNode content = jsonMapper.createArrayNode();
        for (ExtractionInput input : inputs) {
            ObjectNode part = content.addObject();
            switch (input) {
                case ExtractionInput.Text text -> part.put("type", "input_text").put("text", text.text());
                case ExtractionInput.Image image -> part.put("type", "input_image")
                        .put("image_url", dataUrl(image.contentType(), image.content()));
                case ExtractionInput.Pdf pdf -> part.put("type", "input_file")
                        .put("filename", pdf.filename())
                        .put("file_data", dataUrl("application/pdf", pdf.content()));
            }
        }
        ObjectNode message = request.putArray("input").addObject();
        message.put("role", "user");
        message.set("content", content);
        ObjectNode format = request.putObject("text").putObject("format");
        format.put("type", "json_schema");
        format.put("name", schemaName);
        format.put("strict", true);
        format.set("schema", schema);
        return request;
    }

    /** Text of the first output message, parsed as JSON. A refusal or an incomplete answer counts as failure. */
    private JsonNode answer(String body) {
        try {
            JsonNode response = jsonMapper.readTree(body == null ? "" : body);
            for (JsonNode item : response.path("output")) {
                if (!"message".equals(item.path("type").asString(""))) {
                    continue;
                }
                for (JsonNode part : item.path("content")) {
                    if ("output_text".equals(part.path("type").asString(""))) {
                        return jsonMapper.readTree(part.path("text").asString(""));
                    }
                    if ("refusal".equals(part.path("type").asString(""))) {
                        throw new AssistantUnavailableException("assistant refused");
                    }
                }
            }
            log.warn("Assistant answered without output text, status {}", response.path("status").asString("?"));
        } catch (JacksonException e) {
            log.warn("Assistant answer is not JSON: {}", e.getOriginalMessage());
        }
        throw new AssistantUnavailableException("assistant answer unusable");
    }

    private static String dataUrl(String contentType, byte[] content) {
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(content);
    }
}
