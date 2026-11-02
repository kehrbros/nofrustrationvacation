package com.nfv.user.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Error responses of the MCP endpoint as MCP clients expect them (RFC 6750, RFC 9728): {@code 401} with the URL of
 * the protected resource metadata and the scope to request, {@code 403 insufficient_scope} with the scopes needed
 * for the call, so the client can ask the user for more (step-up).
 */
class McpBearerErrors implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final String metadataUrl;

    McpBearerErrors(String metadataUrl) {
        this.metadataUrl = metadataUrl;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) {
        String header = "Bearer resource_metadata=\"" + metadataUrl + "\", scope=\"" + McpProperties.READ_SCOPE + "\"";
        if (exception instanceof OAuth2AuthenticationException) {
            // a token was sent, but it is invalid, expired or not meant for the MCP endpoint
            header += ", error=\"invalid_token\"";
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, header);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) {
        insufficientScope(response, McpProperties.READ_SCOPE);
    }

    void insufficientScope(HttpServletResponse response, String scope) {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\", scope=\"" + scope
                + "\", resource_metadata=\"" + metadataUrl + "\"");
        response.setStatus(HttpStatus.FORBIDDEN.value());
    }
}
