package com.vacantagent.auth.config;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationGrantAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Sets the audience of access tokens to the {@code resource} of the authorization request (RFC 8707), checked by
 * {@link AuthorizationRequestValidator}. A {@code resource} in the token request must be the same one. Without a
 * resource the audience stays the client id (frontend).
 */
final class ResourceAudience {

    private ResourceAudience() {
    }

    static void apply(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        String resource = authorizedResource(context.getAuthorization());
        Object requested = requestedResource(context.getAuthorizationGrant());
        if (requested != null && (resource == null || !(requested instanceof String value)
                || !resource.equals(Resources.canonical(value)))) {
            throw new OAuth2AuthenticationException(new OAuth2Error(AuthorizationRequestValidator.INVALID_TARGET,
                    "OAuth 2.0 Parameter: " + Resources.RESOURCE, null));
        }
        if (resource != null) {
            // mutable list: the claims are stored as JSON with type ids, which only allow common collection types
            context.getClaims().audience(new ArrayList<>(List.of(resource)));
            context.getClaims().claim("client_id", context.getRegisteredClient().getClientId());
        }
    }

    private static String authorizedResource(OAuth2Authorization authorization) {
        if (authorization == null) {
            return null;
        }
        OAuth2AuthorizationRequest request = authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        Object resource = request == null ? null : request.getAdditionalParameters().get(Resources.RESOURCE);
        return resource instanceof String value ? Resources.canonical(value) : null;
    }

    private static Object requestedResource(Authentication grant) {
        if (grant instanceof OAuth2AuthorizationGrantAuthenticationToken token) {
            return token.getAdditionalParameters().get(Resources.RESOURCE);
        }
        return null;
    }
}
