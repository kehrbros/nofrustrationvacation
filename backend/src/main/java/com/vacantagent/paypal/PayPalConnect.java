package com.vacantagent.paypal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vacantagent.paypal.client.PayPalHttp;
import com.vacantagent.paypal.config.PayPalProperties;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * "Log in with PayPal" (OAuth 2.0 authorization code flow): the account owner signs in at PayPal and confirms the
 * account, which proves ownership without us ever seeing the PayPal password, see Docs/paypal.md.
 */
@Component
@RequiredArgsConstructor
public class PayPalConnect {
    /** {@code paypalattributes} delivers payer id and verification status. */
    static final String SCOPES = "openid email https://uri.paypal.com/services/paypalattributes";

    private final PayPalHttp http;
    private final PayPalProperties properties;

    /** Client id and secret are configured, so linking and payments are possible. */
    public boolean isAvailable() {
        return properties.isConfigured();
    }

    /**
     * @param state random value bound to the user, PayPal returns it unchanged to the redirect URI (CSRF protection)
     */
    public URI authorizationUrl(String state) {
        http.requireConfigured();
        return UriComponentsBuilder.fromUriString(properties.environment().webBaseUrl())
                .path("/connect")
                .queryParam("flowEntry", "static")
                .queryParam("client_id", properties.clientId())
                .queryParam("response_type", "code")
                .queryParam("scope", SCOPES)
                .queryParam("redirect_uri", properties.connectRedirectUri())
                .queryParam("state", state)
                .encode()
                .build()
                .toUri();
    }

    /** Exchanges the authorization code from the redirect for the account data. The code is valid only once. */
    public PayPalAccount fetchAccount(String authorizationCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", authorizationCode);
        PayPalHttp.TokenResponse token = http.requestToken(form);
        UserInfo info = http.getAsUser("/v1/identity/oauth2/userinfo?schema=paypalv1.1", token.accessToken(), UserInfo.class);
        if (info == null || info.payerId() == null || info.payerId().isBlank()) {
            throw new PayPalUnavailableException("PayPal userinfo contains no payer id");
        }
        return new PayPalAccount(info.payerId(), info.primaryEmail(), Boolean.parseBoolean(String.valueOf(info.verifiedAccount())));
    }

    record UserInfo(@JsonProperty("payer_id") String payerId,
                    @Nullable List<Email> emails,
                    @JsonProperty("verified_account") @Nullable Object verifiedAccount) {

        @Nullable String primaryEmail() {
            if (emails == null || emails.isEmpty()) return null;
            return emails.stream().filter(Email::primary).findFirst().orElse(emails.getFirst()).value();
        }
    }

    record Email(String value, boolean primary) {
    }
}
