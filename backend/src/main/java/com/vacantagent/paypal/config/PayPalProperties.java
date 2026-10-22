package com.vacantagent.paypal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Access to the PayPal REST API, see Docs/paypal.md. Credentials come from the environment
 * ({@code PAYPAL_CLIENT_ID}, {@code PAYPAL_CLIENT_SECRET}) and are never committed or logged.
 *
 * @param environment           {@code sandbox} or {@code live}, decides the PayPal hosts
 * @param clientId              client id of the PayPal REST app
 * @param clientSecret          secret of the PayPal REST app, excluded from {@link #toString()}
 * @param connectRedirectUri    return URL of "Log in with PayPal", must be registered in the PayPal app exactly
 * @param checkoutReturnUrl     where PayPal sends the payer after approving a payment
 * @param checkoutCancelUrl     where PayPal sends the payer after cancelling a payment
 * @param brandName             shown to the payer on the PayPal pages
 * @param timeout               connect and read timeout of API calls
 */
@ConfigurationProperties("vacantagent.paypal")
public record PayPalProperties(Environment environment, String clientId, String clientSecret,
                               URI connectRedirectUri, URI checkoutReturnUrl, URI checkoutCancelUrl,
                               String brandName, Duration timeout) {

    public enum Environment {
        SANDBOX("https://api-m.sandbox.paypal.com", "https://www.sandbox.paypal.com"),
        LIVE("https://api-m.paypal.com", "https://www.paypal.com");

        private final String apiBaseUrl;
        private final String webBaseUrl;

        Environment(String apiBaseUrl, String webBaseUrl) {
            this.apiBaseUrl = apiBaseUrl;
            this.webBaseUrl = webBaseUrl;
        }

        public String apiBaseUrl() {
            return apiBaseUrl;
        }

        public String webBaseUrl() {
            return webBaseUrl;
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
                + ", connectRedirectUri=" + connectRedirectUri + ", checkoutReturnUrl=" + checkoutReturnUrl
                + ", checkoutCancelUrl=" + checkoutCancelUrl + "]";
    }
}
