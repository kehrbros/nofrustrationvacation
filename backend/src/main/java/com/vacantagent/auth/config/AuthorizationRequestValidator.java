package com.vacantagent.auth.config;

import com.vacantagent.auth.cimd.ClientRegistry;
import com.vacantagent.auth.cimd.RedirectUris;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.net.URI;
import java.util.Collection;
import java.util.function.Consumer;

/**
 * Checks authorization requests in addition to grant type, PKCE and prompt (checked by the authorization server):
 * <ul>
 * <li>redirect URI: exact, for metadata document clients loopback URIs with any port, including {@code localhost}</li>
 * <li>scope: only scopes of the client; with a loopback or private-use scheme redirect URI only the native scopes,
 * because any local program can receive the code (also for clients that have https redirect URIs too)</li>
 * <li>{@code resource} (RFC 8707): required for metadata document clients, always one of the allowed resources;
 * it becomes the audience of the access token</li>
 * </ul>
 */
class AuthorizationRequestValidator implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {
    static final String INVALID_TARGET = "invalid_target";

    private final Collection<String> allowedResources;
    private final Collection<String> nativeScopes;

    AuthorizationRequestValidator(Collection<String> allowedResources, Collection<String> nativeScopes) {
        this.allowedResources = allowedResources;
        this.nativeScopes = nativeScopes;
    }

    @Override
    public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        validateRedirectUri(context);
        OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR.accept(context);
        validateNativeScopes(context);
        validateResource(context);
    }

    private static void validateRedirectUri(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        RegisteredClient client = context.getRegisteredClient();
        if (!ClientRegistry.isCimd(client)) {
            OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_REDIRECT_URI_VALIDATOR.accept(context);
            return;
        }
        OAuth2AuthorizationCodeRequestAuthenticationToken authentication = context.getAuthentication();
        String redirectUri = authentication.getRedirectUri();
        if (redirectUri == null || !RedirectUris.matches(client.getRedirectUris(), redirectUri)) {
            // without a valid redirect URI the error is shown to the user instead of being redirected
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                    new OAuth2Error("invalid_request", "OAuth 2.0 Parameter: " + OAuth2ParameterNames.REDIRECT_URI, null),
                    null);
        }
    }

    private void validateNativeScopes(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        OAuth2AuthorizationCodeRequestAuthenticationToken authentication = context.getAuthentication();
        if (!ClientRegistry.isCimd(context.getRegisteredClient())) {
            return;
        }
        URI redirectUri = RedirectUris.parse(authentication.getRedirectUri());
        boolean https = redirectUri != null && "https".equalsIgnoreCase(redirectUri.getScheme());
        if (!https && !nativeScopes.containsAll(authentication.getScopes())) {
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                    new OAuth2Error(OAuth2ErrorCodes.INVALID_SCOPE, "OAuth 2.0 Parameter: " + OAuth2ParameterNames.SCOPE,
                            null),
                    authentication);
        }
    }

    private void validateResource(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        OAuth2AuthorizationCodeRequestAuthenticationToken authentication = context.getAuthentication();
        Object resource = authentication.getAdditionalParameters().get(Resources.RESOURCE);
        boolean required = ClientRegistry.isCimd(context.getRegisteredClient());
        if (resource == null && !required) {
            return;
        }
        if (!(resource instanceof String value) || !allowedResources.contains(Resources.canonical(value))) {
            throw new OAuth2AuthorizationCodeRequestAuthenticationException(
                    new OAuth2Error(INVALID_TARGET, "OAuth 2.0 Parameter: " + Resources.RESOURCE, null),
                    authentication);
        }
    }
}
