package com.nfv.paypal.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Slf4j
@Configuration
@EnableConfigurationProperties(PayPalProperties.class)
public class PayPalConfig {

    /** Client for the PayPal REST API. Error statuses are handled by the callers, see {@code PayPalHttp}. */
    @Bean
    public RestClient payPalRestClient(PayPalProperties properties) {
        if (properties.isConfigured()) {
            log.info("PayPal {} configured for client {}", properties.environment(), properties.clientId());
        } else {
            log.warn("PayPal is not configured, set PAYPAL_CLIENT_ID and PAYPAL_CLIENT_SECRET, see Docs/paypal.md");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.timeout());
        requestFactory.setReadTimeout(properties.timeout());
        return RestClient.builder()
                .baseUrl(properties.environment().apiBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
