package com.nfv.assistant.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Slf4j
@Configuration
@EnableConfigurationProperties(AssistantProperties.class)
public class AssistantConfig {

    /** Client for the OpenAI API. Error statuses are handled by {@code StructuredExtraction}. */
    @Bean
    public RestClient openAiRestClient(AssistantProperties properties) {
        if (properties.isConfigured()) {
            log.info("Assistant configured with model {}", properties.model());
        } else {
            log.warn("Assistant is not configured (OPENAI_API_KEY empty), bookings can only be entered manually");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        RestClient.Builder builder = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory);
        if (properties.isConfigured()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey().strip());
        }
        return builder.build();
    }
}
