package com.nfv.paypal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Access to the PayPal REST API, see Docs/paypal.md. Credentials come from the environment
 * ({@code PAYPAL_CLIENT_ID}, {@code PAYPAL_CLIENT_SECRET}) and are never committed or logged.
 *
 * @param environment           {@code sandbox} or {@code live}, decides the PayPal hosts
 * @param clientId              client id of the PayPal REST app
 * @param clientSecret          secret of the PayPal REST app, excluded from {@link #toString()}
 * @param brandName             shown to the payer on the PayPal pages
 * @param timeout               connect and read timeout of API calls
 */
@ConfigurationProperties("nfv.paypal")
public record PayPalProperties(Environment environment, String clientId, String clientSecret,
                               String brandName, Duration timeout) {

    public enum Environment {
        SANDBOX("https://api-m.sandbox.paypal.com"),
        LIVE("https://api-m.paypal.com");

        private final String apiBaseUrl;

        Environment(String apiBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
        }

        public String apiBaseUrl() {
            return apiBaseUrl;
        }
    }

    public PayPalProperties {
        if (environment == null) {
            environment = Environment.SANDBOX;
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(20);
        }
    }

    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }

    @Override
    public String toString() {
        return "PayPalProperties[environment=" + environment + ", clientId=" + clientId
                + ", clientSecret=" + (clientSecret == null || clientSecret.isBlank() ? "<missing>" : "<redacted>")
                + ", brandName=" + brandName + ", timeout=" + timeout + "]";
    }
}
