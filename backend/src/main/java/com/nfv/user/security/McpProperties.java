package com.nfv.user.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * The MCP endpoint as OAuth protected resource, see Docs/authentication.md.
 *
 * @param resourceUri URI of the MCP endpoint as clients use it, audience of its access tokens
 * @param readTools   tools that only read, allowed with {@value #READ_SCOPE}. All others need {@value #WRITE_SCOPE}.
 */
@ConfigurationProperties("nfv.mcp")
public record McpProperties(String resourceUri, Set<String> readTools) {
    public static final String READ_SCOPE = "mcp:read";
    public static final String WRITE_SCOPE = "mcp:write";
    public static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    public McpProperties {
        readTools = readTools == null ? Set.of() : Set.copyOf(readTools);
    }

    public List<String> scopes() {
        return List.of(READ_SCOPE, WRITE_SCOPE);
    }

    /**
     * URL of the protected resource metadata (RFC 9728): the well-known path inserted before the path of the resource.
     */
    public String metadataUrl() {
        URI uri = URI.create(resourceUri);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        return uri.getScheme() + "://" + uri.getRawAuthority() + METADATA_PATH + path;
    }
}
