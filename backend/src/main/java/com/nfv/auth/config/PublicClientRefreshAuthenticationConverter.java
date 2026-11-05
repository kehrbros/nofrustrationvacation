package com.nfv.auth.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.authentication.AuthenticationConverter;

import java.util.Map;

/**
 * Client "authentication" of public clients for the refresh token grant and the revocation of their tokens (logout of
 * the frontend): only {@code client_id}, no secret. Spring's {@code PublicClientAuthenticationConverter} only handles
 * token requests with {@code code_verifier}.
 */
class PublicClientRefreshAuthenticationConverter implements AuthenticationConverter {
    static final String PUBLIC_REFRESH = "nfv.public_refresh";
    private static final String REVOCATION_ENDPOINT = AuthorizationServerSettings.builder().build().getTokenRevocationEndpoint();

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())
                || !(isRefresh(request) || isRevocation(request))
                || request.getHeader(HttpHeaders.AUTHORIZATION) != null
                || request.getParameter(OAuth2ParameterNames.CLIENT_SECRET) != null
                || request.getParameter("client_assertion") != null) {
            return null;
        }
        String[] clientIds = request.getParameterValues(OAuth2ParameterNames.CLIENT_ID);
        if (clientIds == null) {
            return null;
        }
        if (clientIds.length != 1 || clientIds[0].isBlank()) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }
        return new OAuth2ClientAuthenticationToken(clientIds[0], ClientAuthenticationMethod.NONE, null,
                Map.of(PUBLIC_REFRESH, true));
    }

    private static boolean isRefresh(HttpServletRequest request) {
        return AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(request.getParameter(OAuth2ParameterNames.GRANT_TYPE));
    }

    /**
     * Revoking needs the token, so anyone able to revoke could use it anyway. Introspection stays closed for public
     * clients.
     */
    private static boolean isRevocation(HttpServletRequest request) {
        return request.getRequestURI().endsWith(REVOCATION_ENDPOINT)
                && request.getParameter(OAuth2ParameterNames.TOKEN) != null;
    }
}
