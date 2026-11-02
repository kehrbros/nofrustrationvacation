package com.nfv.user.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OAuth 2.0 Protected Resource Metadata (RFC 9728) of the MCP endpoint: tells MCP clients which authorization server
 * issues tokens for it. Public, linked from the {@code 401} of {@code /mcp}.
 */
@RestController
public class ProtectedResourceMetadataController {
    private final McpProperties mcp;
    private final String issuer;

    public ProtectedResourceMetadataController(McpProperties mcp,
                                               @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        this.mcp = mcp;
        this.issuer = issuer;
    }

    @GetMapping({McpProperties.METADATA_PATH, McpProperties.METADATA_PATH + "/mcp"})
    public Map<String, Object> metadata() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("resource", mcp.resourceUri());
        metadata.put("authorization_servers", List.of(issuer));
        metadata.put("scopes_supported", mcp.scopes());
        metadata.put("bearer_methods_supported", List.of("header"));
        metadata.put("resource_name", "Nfv MCP");
        return metadata;
    }
}
