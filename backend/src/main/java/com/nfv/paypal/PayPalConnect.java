package com.nfv.paypal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nfv.paypal.client.PayPalHttp;
import com.nfv.paypal.config.PayPalProperties;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Component
@RequiredArgsConstructor
public class PayPalConnect {
    /** {@code paypalattributes} delivers payer id and verification status. */
    static final String SCOPES = "openid email https://uri.paypal.com/services/paypalattributes";
    static final String TOKEN_SERVICE_PATH = "/v1/identity/openidconnect/tokenservice";
    static final String USERINFO_PATH = "/v1/oauth2/token/userinfo?schema=openid";

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

    /**
     * Exchanges the authorization code from the redirect for the account data. The code is valid only once. Endpoints
     * as documented for Payouts recipients ("Log in with PayPal" for Payouts): identity token service, then userinfo.
     */
    public PayPalAccount fetchAccount(String authorizationCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", authorizationCode);
        PayPalHttp.TokenResponse token = http.requestToken(TOKEN_SERVICE_PATH, form);
        UserInfo info;
        try {
            info = http.getAsUser(USERINFO_PATH, token.accessToken(), UserInfo.class);
        } catch (PayPalRejectedException e) {
            // Typically the app does not request the attributes of SCOPES (PayPal app settings, see Docs/paypal.md)
            log.warn("PayPal userinfo refused, requested scopes: {}, granted scopes: {}", SCOPES, token.scope());
            throw e;
        }
        if (info == null || info.payerId() == null || info.payerId().isBlank()) {
            throw new PayPalUnavailableException("PayPal userinfo contains no payer id, is \"PayPal account ID\" "
                    + "requested in the app settings?");
        }
        return new PayPalAccount(info.payerId(), info.primaryEmail(), info.verified());
    }

    /** Covers the {@code openid} schema ({@code email}, {@code email_verified}) and the older {@code paypalv1.1}. */
    record UserInfo(@JsonProperty("payer_id") String payerId,
                    @Nullable String email,
                    @Nullable List<Email> emails,
                    @JsonProperty("verified_account") @Nullable Object verifiedAccount,
                    @JsonProperty("email_verified") @Nullable Object emailVerified) {

        @Nullable String primaryEmail() {
            if (emails != null && !emails.isEmpty()) {
                return emails.stream().filter(Email::primary).findFirst().orElse(emails.getFirst()).value();
            }
            return email;
        }

        boolean verified() {
            return Boolean.parseBoolean(String.valueOf(verifiedAccount != null ? verifiedAccount : emailVerified));
        }
    }

    record Email(String value, boolean primary) {
    }
}
