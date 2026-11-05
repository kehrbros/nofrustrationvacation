package com.nfv;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registration, login via Authorization Code + PKCE and an API call with the issued token. The resource server
 * fetches the signing keys from the running server like it would from an external IdP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
class AuthenticationFlowTest {
    private static final String CLIENT_ID = "nfv-frontend";
    private static final String REDIRECT_URI = "http://localhost:18173/auth/callback";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void serverProperties(DynamicPropertyRegistry registry) throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String issuer = "http://localhost:" + port;
        registry.add("server.port", () -> port);
        registry.add("spring.security.oauth2.authorizationserver.issuer", () -> issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuer);
    }

    @Test
    void apiRequiresToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void registeredUserLogsInAndGetsAppUserOnFirstRequest() throws Exception {
        mockMvc.perform(post("/register").with(csrf())
                        .param("username", "alice").param("password", "secret-password").param("email", "alice@example.org"))
                .andExpect(redirectedUrl("/login?registered"));
        assertEquals(0, countAppUsers("alice"), "registration must not create an AppUser");

        String accessToken = login("alice", "secret-password");

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
        assertEquals(1, countAppUsers("alice"));
    }

    @Test
    void seededTestAccountLogsIn() throws Exception {
        String accessToken = login("test", "test");

        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test"));
    }

    /**
     * The frontend renews its access token silently, the refresh token is rotated and revoked on logout.
     */
    @Test
    void frontendRefreshesAndRevokesTokens() throws Exception {
        JsonNode tokens = tokens("test", "test");
        String refreshToken = tokens.get("refresh_token").asString();

        JsonNode refreshed = JSON.readTree(mockMvc.perform(refreshRequest(refreshToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String rotated = refreshed.get("refresh_token").asString();
        assertNotEquals(refreshToken, rotated, "refresh tokens are rotated");
        assertNotNull(refreshed.get("id_token"));
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + refreshed.get("access_token").asString()))
                .andExpect(status().isOk());
        mockMvc.perform(refreshRequest(refreshToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));

        mockMvc.perform(post("/oauth2/revoke")
                        .param("token", rotated)
                        .param("token_type_hint", "refresh_token")
                        .param("client_id", CLIENT_ID))
                .andExpect(status().isOk());
        mockMvc.perform(refreshRequest(rotated))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void duplicateUsernameIsRejected() throws Exception {
        mockMvc.perform(post("/register").with(csrf()).param("username", "bob").param("password", "secret-password"))
                .andExpect(redirectedUrl("/login?registered"));
        mockMvc.perform(post("/register").with(csrf()).param("username", "bob").param("password", "other-password"))
                .andExpect(status().isOk());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_account WHERE username = 'bob'", Integer.class));
    }

    /**
     * Authorization Code flow with PKCE as the frontend runs it.
     */
    private String login(String username, String password) throws Exception {
        return tokens(username, password).get("access_token").asString();
    }

    private JsonNode tokens(String username, String password) throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/login").with(csrf())
                        .param("username", username).param("password", password))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession();

        String codeVerifier = "a-code-verifier-that-is-long-enough-for-pkce-0123456789";
        String codeChallenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII)));
        // the authorization endpoint only reads parameters from the query string
        String authorizationRequest = UriComponentsBuilder.fromPath("/oauth2/authorize")
                .queryParam("response_type", "code")
                .queryParam("client_id", CLIENT_ID)
                .queryParam("redirect_uri", REDIRECT_URI)
                .queryParam("scope", "openid profile email")
                .queryParam("state", "state")
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .encode().build().toUriString();
        String location = mockMvc.perform(get(URI.create(authorizationRequest)).session(session))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        assertNotNull(location);
        String code = UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("code");
        assertNotNull(code, location);

        String tokenResponse = mockMvc.perform(post("/oauth2/token")
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT_URI)
                        .param("client_id", CLIENT_ID)
                        .param("code_verifier", codeVerifier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(tokenResponse);
    }

    private static org.springframework.test.web.servlet.RequestBuilder refreshRequest(String refreshToken) {
        return post("/oauth2/token")
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken)
                .param("client_id", CLIENT_ID);
    }

    private int countAppUsers(String username) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, username);
    }
}
