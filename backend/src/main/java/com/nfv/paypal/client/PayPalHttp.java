package com.nfv.paypal.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nfv.paypal.PayPalNotConfiguredException;
import com.nfv.paypal.PayPalRejectedException;
import com.nfv.paypal.PayPalUnavailableException;
import com.nfv.paypal.config.PayPalProperties;
import jakarta.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * Executes calls against the PayPal REST API with an app access token (client credentials, cached until shortly
 * before it expires) and maps errors: 4xx to {@link PayPalRejectedException}, 5xx and I/O errors to
 * {@link PayPalUnavailableException}. Tokens and secrets are never logged.
 */
@Slf4j
@Component
public class PayPalHttp {
    private static final long TOKEN_EXPIRY_MARGIN_SECONDS = 60;

    private final RestClient restClient;
    private final PayPalProperties properties;
    private final Clock clock;
    private volatile @Nullable CachedToken token;

    public PayPalHttp(@Qualifier("payPalRestClient") RestClient restClient, PayPalProperties properties, Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.clock = clock;
    }

    public <T> T get(String path, Class<T> responseType) {
        return withAppToken(token -> restClient.get().uri(path)
                .headers(headers -> headers.setBearerAuth(token))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (_, response) -> { throw toException(response.getStatusCode(), response.getBody().readAllBytes()); })
                .body(responseType));
    }

    /**
     * @param requestId {@code PayPal-Request-Id}: PayPal executes requests with the same id only once and answers
     *                  repeats with the first result, so a call with an unknown outcome can be repeated safely
     */
    public <T> T post(String path, Object body, Class<T> responseType, @Nullable String requestId) {
        return withAppToken(token -> restClient.post().uri(path)
                .headers(headers -> {
                    headers.setBearerAuth(token);
                    headers.set("Prefer", "return=representation");
                    if (requestId != null) {
                        headers.set("PayPal-Request-Id", requestId);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (_, response) -> { throw toException(response.getStatusCode(), response.getBody().readAllBytes()); })
                .body(responseType));
    }

    /** Token request with the app credentials (Basic auth), e.g. to exchange an authorization code. */
    public TokenResponse requestToken(MultiValueMap<String, String> form) {
        return requestToken("/v1/oauth2/token", form);
    }

    /** Token request at another token endpoint, e.g. the identity token service of "Log in with PayPal". */
    public TokenResponse requestToken(String path, MultiValueMap<String, String> form) {
        requireConfigured();
        try {
            TokenResponse response = restClient.post().uri(path)
                    .headers(headers -> headers.setBasicAuth(properties.clientId(), properties.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (_, r) -> { throw toException(r.getStatusCode(), r.getBody().readAllBytes()); })
                    .body(TokenResponse.class);
            if (response == null || response.accessToken() == null) {
                throw new PayPalUnavailableException("PayPal returned no access token");
            }
            return response;
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    /** Calls the API with the bearer token of a user (e.g. userinfo of "Log in with PayPal"). */
    public <T> T getAsUser(String path, String userAccessToken, Class<T> responseType) {
        try {
            return restClient.get().uri(path)
                    .headers(headers -> headers.setBearerAuth(userAccessToken))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (_, r) -> { throw toException(r.getStatusCode(), r.getBody().readAllBytes()); })
                    .body(responseType);
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    public void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new PayPalNotConfiguredException();
        }
    }

    private <T> T withAppToken(Function<String, T> call) {
        try {
            try {
                return call.apply(appToken());
            } catch (PayPalRejectedException e) {
                if (e.getStatus() != 401) {
                    throw e;
                }
                // token revoked or expired early: fetch a new one and try once more
                token = null;
                return call.apply(appToken());
            }
        } catch (RestClientException e) {
            throw unavailable(e);
        }
    }

    private String appToken() {
        CachedToken current = token;
        Instant now = clock.instant();
        if (current != null && now.isBefore(current.expiresAt())) {
            return current.value();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        TokenResponse response = requestToken(form);
        long lifetime = response.expiresIn() == null ? 0 : response.expiresIn();
        token = new CachedToken(response.accessToken(), now.plusSeconds(Math.max(0, lifetime - TOKEN_EXPIRY_MARGIN_SECONDS)));
        return response.accessToken();
    }

    private static PayPalUnavailableException unavailable(RestClientException e) {
        return new PayPalUnavailableException("PayPal request failed: " + e.getMessage(), e);
    }

    private static RuntimeException toException(HttpStatusCode status, byte[] body) {
        ErrorResponse error = ErrorResponse.parse(body);
        if (status.is4xxClientError()) {
            return new PayPalRejectedException(status.value(), error.issue(), error.debugId(), error.message());
        }
        return new PayPalUnavailableException("PayPal answered " + status.value() + " (debug id " + error.debugId() + "): "
                + error.message());
    }

    private record CachedToken(String value, Instant expiresAt) {
    }

    /** {@code scope}: granted scopes, separated by spaces (only in some responses). */
    public record TokenResponse(@JsonProperty("access_token") String accessToken,
                                @JsonProperty("expires_in") Long expiresIn,
                                @Nullable String scope) {
    }

    /** PayPal API errors ({@code name}, {@code details[].issue}) and OAuth errors ({@code error}). */
    record ErrorResponse(@Nullable String issue, @Nullable String debugId, String message) {
        private static final tools.jackson.databind.ObjectMapper MAPPER = new tools.jackson.databind.ObjectMapper();

        static ErrorResponse parse(byte[] body) {
            try {
                tools.jackson.databind.JsonNode json = MAPPER.readTree(body);
                String issue = text(json.path("details").path(0).path("issue"));
                if (issue == null) issue = text(json.path("name"));
                if (issue == null) issue = text(json.path("error"));
                String message = text(json.path("message"));
                if (message == null) message = text(json.path("error_description"));
                return new ErrorResponse(issue, text(json.path("debug_id")), message == null ? "" : message);
            } catch (RuntimeException e) {
                return new ErrorResponse(null, null, "unreadable error response");
            }
        }

        private static @Nullable String text(tools.jackson.databind.JsonNode node) {
            return node.isValueNode() ? node.asString() : null;
        }
    }

    /** Link of a PayPal resource, e.g. {@code rel=payer-action}. */
    public record Link(String href, String rel) {
        public static @Nullable String find(@Nullable List<Link> links, String rel) {
            if (links == null) return null;
            return links.stream().filter(link -> rel.equals(link.rel())).map(Link::href).findFirst().orElse(null);
        }
    }
}
