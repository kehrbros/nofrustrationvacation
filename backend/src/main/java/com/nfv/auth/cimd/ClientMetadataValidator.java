package com.nfv.auth.cimd;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Checks {@code client_id} URLs and Client ID Metadata Documents (draft-ietf-oauth-client-id-metadata-document,
 * MCP authorization specification). Only public clients ({@code token_endpoint_auth_method=none}) with Authorization
 * Code + PKCE are supported.
 */
public final class ClientMetadataValidator {
    private static final int MAX_CLIENT_ID_LENGTH = 2048;
    private static final int MAX_CLIENT_NAME_LENGTH = 100;
    private static final int MAX_REDIRECT_URIS = 20;
    private static final Set<String> GRANT_TYPES = Set.of("authorization_code", "refresh_token");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ClientMetadataValidator() {
    }

    /**
     * Whether the {@code client_id} is meant as a metadata document URL (and not a pre-registered client).
     */
    public static boolean isUrl(String clientId) {
        return clientId != null && clientId.regionMatches(true, 0, "https://", 0, "https://".length());
    }

    /**
     * A {@code client_id} URL must be https on the default port, with a path and without query, fragment, user info
     * or dot segments, so the allowlist prefixes cannot be bypassed.
     */
    public static URI validateClientId(String clientId) {
        if (clientId.length() > MAX_CLIENT_ID_LENGTH) {
            throw new CimdException("client_id too long");
        }
        URI uri;
        try {
            uri = new URI(clientId);
        } catch (Exception e) {
            throw new CimdException("client_id is not a URI", e);
        }
        String path = uri.getRawPath();
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getRawQuery() != null
                || uri.getRawFragment() != null || path == null || path.length() < 2
                || !uri.normalize().toString().equals(clientId) || path.contains("/./") || path.contains("/../")
                || path.endsWith("/.") || path.endsWith("/..") || path.contains("%2e") || path.contains("%2E")) {
            throw new CimdException("client_id is not a valid https URL with a path: " + clientId);
        }
        return uri;
    }

    /**
     * Parses and validates the document fetched from {@code clientId}.
     */
    public static ClientMetadata parse(String clientId, String json) {
        JsonNode document;
        try {
            document = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new CimdException("document is not JSON", e);
        }
        if (document == null || !document.isObject()) {
            throw new CimdException("document is not a JSON object");
        }
        if (!clientId.equals(text(document, "client_id"))) {
            throw new CimdException("client_id in the document does not match its URL");
        }
        String clientName = sanitize(text(document, "client_name"));
        if (clientName == null || clientName.isBlank()) {
            throw new CimdException("client_name missing");
        }
        String authMethod = text(document, "token_endpoint_auth_method");
        if (authMethod != null && !authMethod.equals("none")) {
            throw new CimdException("token_endpoint_auth_method " + authMethod + " not supported, only none");
        }
        for (String grantType : strings(document, "grant_types")) {
            if (!GRANT_TYPES.contains(grantType)) {
                throw new CimdException("grant type " + grantType + " not supported");
            }
        }
        for (String responseType : strings(document, "response_types")) {
            if (!responseType.equals("code")) {
                throw new CimdException("response type " + responseType + " not supported");
            }
        }
        List<String> redirectUris = strings(document, "redirect_uris");
        if (redirectUris.isEmpty() || redirectUris.size() > MAX_REDIRECT_URIS) {
            throw new CimdException("redirect_uris missing or too many");
        }
        String clientHost = URI.create(clientId).getHost();
        for (String redirectUri : redirectUris) {
            validateRedirectUri(redirectUri, clientHost);
        }
        return new ClientMetadata(clientId, clientName, redirectUris);
    }

    private static void validateRedirectUri(String redirectUri, String clientHost) {
        URI uri = RedirectUris.parse(redirectUri);
        if (uri == null || !uri.isAbsolute() || uri.getRawFragment() != null) {
            throw new CimdException("invalid redirect URI " + redirectUri);
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        if (!https && !RedirectUris.isLoopback(uri) && !RedirectUris.isPrivateUseScheme(uri, clientHost)) {
            throw new CimdException("redirect URI " + redirectUri + " is neither https, loopback nor a private-use "
                    + "scheme of " + clientHost);
        }
    }

    private static String text(JsonNode document, String field) {
        JsonNode value = document.get(field);
        return value != null && value.isString() ? value.asString() : null;
    }

    private static List<String> strings(JsonNode document, String field) {
        JsonNode value = document.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        if (!value.isArray()) {
            throw new CimdException(field + " is not an array");
        }
        List<String> result = new ArrayList<>();
        for (JsonNode element : value) {
            if (!element.isString()) {
                throw new CimdException(field + " contains a non-string value");
            }
            result.add(element.asString());
        }
        return result;
    }

    /**
     * The name is shown on the consent page: no control characters, limited length.
     */
    private static String sanitize(String name) {
        if (name == null) {
            return null;
        }
        String cleaned = name.replaceAll("\\p{C}", "").strip();
        return cleaned.length() > MAX_CLIENT_NAME_LENGTH ? cleaned.substring(0, MAX_CLIENT_NAME_LENGTH) : cleaned;
    }
}
