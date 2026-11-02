package com.nfv.auth.config;

import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Resource indicators (RFC 8707). The MCP client sends the URI of the MCP server as {@code resource}, it becomes the
 * audience of the access token, so a token for the MCP server is not accepted elsewhere.
 */
final class Resources {
    static final String RESOURCE = OAuth2ParameterNames.RESOURCE;

    private Resources() {
    }

    /**
     * Comparable form: lower case scheme and host, without trailing slash. Invalid URIs are returned unchanged and
     * then do not match any allowed resource.
     */
    static String canonical(String resource) {
        try {
            URI uri = new URI(resource.trim());
            if (uri.getScheme() == null || uri.getRawAuthority() == null || uri.getRawFragment() != null) {
                return resource;
            }
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            while (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority().toLowerCase(Locale.ROOT)
                    + path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        } catch (URISyntaxException e) {
            return resource;
        }
    }
}
