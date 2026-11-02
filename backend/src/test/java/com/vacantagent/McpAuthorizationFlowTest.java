package com.vacantagent;

import com.vacantagent.auth.cimd.CimdClientResolver;
import com.vacantagent.auth.cimd.ClientMetadataSource;
import com.vacantagent.auth.cimd.CimdException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * An MCP client connects as the MCP authorization spec describes it: discovery from the {@code 401} of {@code /mcp},
 * client identified by its metadata document URL (served by a stub instead of fetched), login, consent, Authorization
 * Code + PKCE with resource indicator, token refresh, scopes per tool and revoke on the connections page.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
class McpAuthorizationFlowTest {
    private static final String WEB_CLIENT = "https://client.example/oauth/metadata.json";
    private static final String WEB_REDIRECT = "https://client.example/oauth/callback";
    private static final String NATIVE_CLIENT = "https://cli.example/oauth/metadata.json";
    private static final String UNLISTED_CLIENT = "https://evil.example/oauth/metadata.json";
    /** Tool names only matter to the scope check, the server does not know these tools. */
    private static final String READ_TOOL = "read_tool";
    private static final String WRITE_TOOL = "write_tool";
    private static final String CODE_VERIFIER ="a-code-verifier-that-is-long-enough-for-pkce-0123456789";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, String> DOCUMENTS = new ConcurrentHashMap<>();
    private static final AtomicInteger FETCHES = new AtomicInteger();
    private static String issuer;

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void serverProperties(DynamicPropertyRegistry registry) throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        issuer = "http://localhost:" + port;
        registry.add("server.port", () -> port);
        registry.add("spring.security.oauth2.authorizationserver.issuer", () -> issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuer);
        registry.add("vacantagent.mcp.resource-uri", () -> issuer + "/mcp");
        registry.add("vacantagent.mcp.read-tools", () -> READ_TOOL);
        registry.add("vacantagent.auth.cimd.allowed-client-ids", () -> WEB_CLIENT + "," + NATIVE_CLIENT);
        DOCUMENTS.put(WEB_CLIENT, document(WEB_CLIENT, "Example Assistant", WEB_REDIRECT, "http://127.0.0.1/callback"));
        DOCUMENTS.put(NATIVE_CLIENT, document(NATIVE_CLIENT, "Example CLI", "http://127.0.0.1/callback",
                "http://localhost/callback"));
        DOCUMENTS.put(UNLISTED_CLIENT, document(UNLISTED_CLIENT, "Evil", "https://evil.example/callback"));
    }

    @TestConfiguration
    static class StubDocuments {
        @Bean
        @Primary
        ClientMetadataSource stubClientMetadataSource() {
            return uri -> {
                FETCHES.incrementAndGet();
                String json = DOCUMENTS.get(uri.toString());
                if (json == null) {
                    throw new CimdException("not found");
                }
                return new ClientMetadataSource.Document(json, Duration.ofHours(1));
            };
        }
    }

    @Test
    void unauthenticatedRequestPointsToMetadata() throws Exception {
        mockMvc.perform(post("/mcp").contentType(MediaType.APPLICATION_JSON).content(initialize()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString(
                        "resource_metadata=\"" + issuer + "/.well-known/oauth-protected-resource/mcp\"")))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("scope=\"mcp:read\"")));

        mockMvc.perform(get("/.well-known/oauth-protected-resource/mcp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value(issuer + "/mcp"))
                .andExpect(jsonPath("$.authorization_servers[0]").value(issuer))
                .andExpect(jsonPath("$.scopes_supported").value(hasItem("mcp:write")));

        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.client_id_metadata_document_supported").value(true))
                .andExpect(jsonPath("$.token_endpoint_auth_methods_supported").value(hasItem("none")))
                .andExpect(jsonPath("$.code_challenge_methods_supported").value(hasItem("S256")));
        mockMvc.perform(get("/.well-known/openid-configuration"))
                .andExpect(jsonPath("$.client_id_metadata_document_supported").value(true));
    }

    @Test
    void webClientGetsReadAndWriteAccessAndRefreshes() throws Exception {
        MockHttpSession session = login();
        Tokens tokens = authorize(session, WEB_CLIENT, WEB_REDIRECT, "mcp:read mcp:write");

        JsonNode claims = claims(tokens.accessToken());
        assertEquals(issuer + "/mcp", audience(claims));
        assertEquals(WEB_CLIENT, claims.get("client_id").asString());
        assertEquals("test", claims.get("sub").asString());
        assertNotNull(tokens.refreshToken(), "MCP clients need refresh tokens");

        mockMvc.perform(mcp(tokens.accessToken(), toolCall(WRITE_TOOL))).andExpect(notRejected());
        mockMvc.perform(mcp(tokens.accessToken(), initialize()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"serverInfo\"")));
        // tokens for the MCP endpoint are not accepted by the API and vice versa
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.accessToken()))
                .andExpect(status().isUnauthorized());

        Tokens refreshed = refresh(WEB_CLIENT, tokens.refreshToken());
        assertNotEquals(tokens.refreshToken(), refreshed.refreshToken(), "refresh tokens are rotated");
        assertEquals(issuer + "/mcp", audience(claims(refreshed.accessToken())));
        mockMvc.perform(refreshRequest(WEB_CLIENT, tokens.refreshToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        // a refresh token is bound to its client
        mockMvc.perform(refreshRequest(NATIVE_CLIENT, refreshed.refreshToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void readScopeAllowsOnlyReadingTools() throws Exception {
        MockHttpSession session = login();
        Tokens tokens = authorize(session, WEB_CLIENT, WEB_REDIRECT, "mcp:read");

        mockMvc.perform(mcp(tokens.accessToken(), toolCall(READ_TOOL))).andExpect(notRejected());
        mockMvc.perform(mcp(tokens.accessToken(), toolCall(WRITE_TOOL)))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error=\"insufficient_scope\"")))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("scope=\"mcp:read mcp:write\"")));
        mockMvc.perform(mcp(tokens.accessToken(), "[" + toolCall(READ_TOOL) + "," + toolCall(WRITE_TOOL) + "]"))
                .andExpect(status().isForbidden());
        mockMvc.perform(mcp(tokens.accessToken(), "not json")).andExpect(status().isForbidden());
    }

    @Test
    void nativeClientGetsReadAccessOnlyWithLoopbackOnAnyPort() throws Exception {
        MockHttpSession session = login();
        Tokens tokens = authorize(session, NATIVE_CLIENT, "http://127.0.0.1:53682/callback", "mcp:read");
        assertEquals("mcp:read", claims(tokens.accessToken()).get("scope").get(0).asString());
        authorize(session, NATIVE_CLIENT, "http://localhost:41234/callback", "mcp:read");

        String location = authorizeRedirect(session, NATIVE_CLIENT, "http://127.0.0.1:53682/callback",
                "mcp:read mcp:write", issuer + "/mcp");
        assertEquals("invalid_scope", query(location).getFirst("error"));
        // also a client with https redirect URIs gets write access only via https
        location = authorizeRedirect(session, WEB_CLIENT, "http://127.0.0.1:53682/callback", "mcp:read mcp:write",
                issuer + "/mcp");
        assertEquals("invalid_scope", query(location).getFirst("error"));
    }

    @Test
    void rejectsUnknownClientsRedirectsAndResources() throws Exception {
        MockHttpSession session = login();
        // not on the allowlist: never fetched, no redirect to the client
        int fetches = FETCHES.get();
        mockMvc.perform(get(authorizeUri(UNLISTED_CLIENT, "https://evil.example/callback", "mcp:read", issuer + "/mcp"))
                        .session(session))
                .andExpect(status().isBadRequest());
        assertEquals(fetches, FETCHES.get());
        // redirect URI not in the document
        mockMvc.perform(get(authorizeUri(WEB_CLIENT, "https://attacker.example/callback", "mcp:read", issuer + "/mcp"))
                        .session(session))
                .andExpect(status().isBadRequest());
        // resource missing or not the MCP endpoint
        assertEquals("invalid_target", query(authorizeRedirect(session, WEB_CLIENT, WEB_REDIRECT, "mcp:read", null))
                .getFirst("error"));
        assertEquals("invalid_target", query(authorizeRedirect(session, WEB_CLIENT, WEB_REDIRECT, "mcp:read",
                "https://other.example/mcp")).getFirst("error"));
    }

    @Test
    void connectionCanBeRevoked() throws Exception {
        MockHttpSession session = login();
        Tokens tokens = authorize(session, WEB_CLIENT, WEB_REDIRECT, "mcp:read mcp:write");

        String id = CimdClientResolver.registeredClientId(WEB_CLIENT);
        mockMvc.perform(get("/account/connections").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Example Assistant")))
                .andExpect(content().string(containsString(id)));

        mockMvc.perform(post("/account/connections/revoke").session(session).with(csrf()).param("id", id))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/account/connections").session(session))
                .andExpect(content().string(not(containsString("Example Assistant"))));
        mockMvc.perform(refreshRequest(WEB_CLIENT, tokens.refreshToken()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        // without consent the client has to ask again
        String location = authorizeRedirect(session, WEB_CLIENT, WEB_REDIRECT, "mcp:read", issuer + "/mcp");
        assertTrue(location.contains("/oauth2/consent"), location);
    }

    @Test
    void connectionsPageRequiresLogin() throws Exception {
        mockMvc.perform(get("/account/connections")).andExpect(status().is3xxRedirection());
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mockMvc.perform(post("/login").with(csrf()).param("username", "test").param("password", "test"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession();
    }

    /**
     * Authorization request, consent with the given scopes and token request, as an MCP client runs it.
     */
    private Tokens authorize(MockHttpSession session, String clientId, String redirectUri, String scope) throws Exception {
        String location = authorizeRedirect(session, clientId, redirectUri, scope, issuer + "/mcp");
        if (location.contains("/oauth2/consent")) {
            MultiValueMap<String, String> consent = query(location);
            String state = consent.getFirst("state");
            mockMvc.perform(get(URI.create(location)).session(session))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(clientId.equals(NATIVE_CLIENT) ? "Example CLI" : "Example Assistant")));
            var request = post("/oauth2/authorize").session(session)
                    .param("client_id", clientId).param("state", state);
            for (String value : scope.split(" ")) {
                request.param("scope", value);
            }
            location = mockMvc.perform(request).andExpect(status().is3xxRedirection())
                    .andReturn().getResponse().getRedirectedUrl();
        }
        assertNotNull(location);
        assertTrue(location.startsWith(redirectUri), location);
        String code = query(location).getFirst("code");
        assertNotNull(code, location);

        String response = mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", redirectUri)
                        .param("client_id", clientId)
                        .param("code_verifier", CODE_VERIFIER)
                        .param("resource", issuer + "/mcp"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode token = JSON.readTree(response);
        return new Tokens(token.get("access_token").asString(),
                token.has("refresh_token") ? token.get("refresh_token").asString() : null);
    }

    private String authorizeRedirect(MockHttpSession session, String clientId, String redirectUri, String scope,
                                     String resource) throws Exception {
        String location = mockMvc.perform(get(authorizeUri(clientId, redirectUri, scope, resource)).session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertNotNull(location);
        return location;
    }

    private static URI authorizeUri(String clientId, String redirectUri, String scope, String resource) throws Exception {
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(CODE_VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", scope)
                .queryParam("state", "client-state")
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256");
        if (resource != null) {
            builder.queryParam("resource", resource);
        }
        return URI.create(builder.encode().build().toUriString());
    }

    private Tokens refresh(String clientId, String refreshToken) throws Exception {
        String response = mockMvc.perform(refreshRequest(clientId, refreshToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode token = JSON.readTree(response);
        return new Tokens(token.get("access_token").asString(), token.get("refresh_token").asString());
    }

    private static org.springframework.test.web.servlet.RequestBuilder refreshRequest(String clientId, String refreshToken) {
        return post("/oauth2/token")
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken)
                .param("client_id", clientId)
                .param("resource", issuer + "/mcp");
    }

    private static org.springframework.test.web.servlet.RequestBuilder mcp(String accessToken, String body) {
        return post("/mcp")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /**
     * The request reached the MCP server: neither rejected by authentication nor by the scope check.
     */
    private static org.springframework.test.web.servlet.ResultMatcher notRejected() {
        return result -> {
            int status = result.getResponse().getStatus();
            assertTrue(status != 401 && status != 403, "status " + status);
            assertNull(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE));
        };
    }

    private static String initialize() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
    }

    private static String toolCall(String tool) {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                + "\",\"arguments\":{}}}";
    }

    private static JsonNode claims(String jwt) {
        return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    /**
     * A single audience is serialized as string.
     */
    private static String audience(JsonNode claims) {
        JsonNode aud = claims.get("aud");
        return aud.isArray() && aud.size() == 1 ? aud.get(0).asString() : aud.asString();
    }

    private static MultiValueMap<String, String> query(String location) {
        MultiValueMap<String, String> decoded = new LinkedMultiValueMap<>();
        UriComponentsBuilder.fromUriString(location).build().getQueryParams().forEach((name, values) ->
                values.forEach(value -> decoded.add(name, URLDecoder.decode(value, StandardCharsets.UTF_8))));
        return decoded;
    }

    private static String document(String clientId, String name, String... redirectUris) {
        StringBuilder uris = new StringBuilder();
        for (String uri : redirectUris) {
            uris.append(uris.isEmpty() ? "" : ",").append('"').append(uri).append('"');
        }
        return "{\"client_id\":\"" + clientId + "\",\"client_name\":\"" + name + "\",\"redirect_uris\":[" + uris
                + "],\"grant_types\":[\"authorization_code\",\"refresh_token\"],\"response_types\":[\"code\"],"
                + "\"token_endpoint_auth_method\":\"none\"}";
    }

    private record Tokens(String accessToken, String refreshToken) {
    }
}
