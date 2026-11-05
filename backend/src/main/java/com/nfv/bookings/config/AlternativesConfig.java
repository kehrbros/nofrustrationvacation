package com.nfv.bookings.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Slf4j
@Configuration
@EnableConfigurationProperties(AlternativesProperties.class)
public class AlternativesConfig {

    /** Client for LiteAPI, absolute URLs (search and book have different hosts). Errors are mapped in {@code LiteApi}. */
    @Bean
    public RestClient liteApiRestClient(AlternativesProperties properties) {
        if (properties.isConfigured()) {
            log.info("LiteAPI configured{}", properties.apiKey().strip().startsWith("sand_") ? " (sandbox)" : "");
        } else {
            log.warn("LiteAPI is not configured (LITEAPI_API_KEY empty), no search for alternative accommodations");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        if (properties.isConfigured()) {
            builder.defaultHeader("X-API-Key", properties.apiKey().strip());
        }
        return builder.build();
    }
}
