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
import tools.jackson.databind.node.ObjectNode;

import java.util.Base64;

/**
 * Lets the image model draw a picture from a prompt (OpenAI Images API). Knows no business module: callers bring the
 * prompt. Counts against the same per-user limit as {@link StructuredExtraction}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGeneration {
    private final RestClient openAiRestClient;
    private final AssistantProperties properties;
    private final RateLimiter rateLimiter;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public boolean isAvailable() {
        return properties.isConfigured();
    }

    /** A landscape JPEG (1536×1024) for the prompt. */
    public GeneratedImage generate(long appUserId, String prompt) {
        if (!isAvailable()) {
            throw new AssistantUnavailableException("assistant is not configured");
        }
        rateLimiter.acquire(appUserId);
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("model", properties.imageModel());
        request.put("prompt", prompt);
        request.put("n", 1);
        request.put("size", "1536x1024");
        request.put("quality", properties.imageQuality());
        request.put("output_format", "jpeg");
        request.put("output_compression", 85);
        String body;
        try {
            body = openAiRestClient.post().uri("/images/generations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(request))
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            log.warn("Image generation failed: {}", e.getMessage());
            throw new AssistantUnavailableException("image generation failed");
        }
        return image(body);
    }

    /** Base64 data of the first image. */
    private GeneratedImage image(String body) {
        try {
            JsonNode response = jsonMapper.readTree(body == null ? "" : body);
            String data = response.path("data").path(0).path("b64_json").asString("");
            if (!data.isEmpty()) {
                return new GeneratedImage(MediaType.IMAGE_JPEG_VALUE, Base64.getDecoder().decode(data));
            }
            log.warn("Image generation answered without image data");
        } catch (JacksonException e) {
            log.warn("Image generation answer is not JSON: {}", e.getOriginalMessage());
        } catch (IllegalArgumentException e) {
            log.warn("Image generation answer is not Base64");
        }
        throw new AssistantUnavailableException("image generation answer unusable");
    }

    /** Image bytes with their media type, e.g. {@code image/jpeg}. */
    public record GeneratedImage(String contentType, byte[] content) {
    }
}
