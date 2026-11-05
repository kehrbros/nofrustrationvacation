package com.nfv.auth.config;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Accepts the public client of {@link PublicClientRefreshAuthenticationConverter} if it is a public client with the
 * refresh token grant. That the token belongs to this client is checked by the refresh token grant and the revocation.
 */
class PublicClientRefreshAuthenticationProvider implements AuthenticationProvider {
    private final RegisteredClientRepository clients;

    PublicClientRefreshAuthenticationProvider(RegisteredClientRepository clients) {
        this.clients = clients;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
        if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())
                || !Boolean.TRUE.equals(token.getAdditionalParameters().get(PublicClientRefreshAuthenticationConverter.PUBLIC_REFRESH))) {
            return null;
        }
        RegisteredClient client = clients.findByClientId(token.getPrincipal().toString());
        if (client == null
                || !client.getClientAuthenticationMethods().contains(ClientAuthenticationMethod.NONE)
                || !client.getAuthorizationGrantTypes().contains(AuthorizationGrantType.REFRESH_TOKEN)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(OAuth2ErrorCodes.INVALID_CLIENT,
                    "Client authentication failed: client_id", null));
        }
        return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
