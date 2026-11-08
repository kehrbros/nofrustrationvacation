package com.nfv.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Outdated login forms (double submit, back button, expired session) and the error page that replaces Spring's
 * Whitelabel page. The error page is rendered by the server's error dispatch, so those requests go over HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureMockMvc
class StaleLoginFormTest {
    private static final String APP = "http://localhost:18173/";
    private static int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void serverProperties(DynamicPropertyRegistry registry) throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String issuer = "http://localhost:" + port;
        registry.add("server.port", () -> port);
        registry.add("spring.security.oauth2.authorizationserver.issuer", () -> issuer);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> issuer);
    }

    @Test
    void secondSubmitAfterLoginContinuesInTheApp() throws Exception {
        MockHttpSession session = (MockHttpSession) mockMvc.perform(post("/login").with(csrf())
                        .param("username", "test").param("password", "test"))
                .andExpect(redirectedUrl(APP))
                .andReturn().getRequest().getSession();

        // the first login replaced the CSRF token the form still carries
        mockMvc.perform(post("/login").session(session).with(csrf().useInvalidToken())
                        .param("username", "test").param("password", "test"))
                .andExpect(redirectedUrl(APP));
    }

    @Test
    void expiredLoginFormIsShownAgainWithNote() throws Exception {
        mockMvc.perform(post("/login").with(csrf().useInvalidToken())
                        .param("username", "test").param("password", "test"))
                .andExpect(redirectedUrl("/login?expired"));
        mockMvc.perform(post("/register").with(csrf().useInvalidToken())
                        .param("username", "carol").param("password", "secret-password"))
                .andExpect(redirectedUrl("/register?expired"));

        mockMvc.perform(get("/login").param("expired", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("zu lange geöffnet")));
    }

    @Test
    void loginWithoutSessionOverHttpLeadsBackToTheForm() throws Exception {
        HttpResponse<String> response = send(HttpRequest.newBuilder(uri("/login"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=test&password=test")));
        assertEquals(302, response.statusCode());
        assertTrue(response.headers().firstValue("Location").orElseThrow().endsWith("/login?expired"));
    }

    @Test
    void errorPageInsteadOfWhitelabel() throws Exception {
        HttpResponse<String> forbidden = send(html("/logout", "de-DE,de;q=0.9")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.noBody()));
        assertEquals(403, forbidden.statusCode());
        assertTrue(forbidden.body().contains("Das hat nicht geklappt"), forbidden.body());
        assertTrue(forbidden.body().contains("href=\"" + APP + "\""), forbidden.body());
        assertTrue(!forbidden.body().contains("Whitelabel"));

        HttpResponse<String> german = send(html("/does-not-exist", "de").GET());
        assertEquals(404, german.statusCode());
        assertTrue(german.body().contains("Seite nicht gefunden"), german.body());

        HttpResponse<String> english = send(html("/does-not-exist", "en-US").GET());
        assertTrue(english.body().contains("Page not found"), english.body());
        // other languages fall back to English
        HttpResponse<String> french = send(html("/does-not-exist", "fr").GET());
        assertTrue(french.body().contains("Page not found"), french.body());

        HttpResponse<String> json = send(HttpRequest.newBuilder(uri("/does-not-exist")).header("Accept", "application/json").GET());
        assertEquals(404, json.statusCode());
        assertTrue(json.body().contains("\"status\":404"), json.body());
    }

    private HttpRequest.Builder html(String path, String language) {
        return HttpRequest.newBuilder(uri(path)).header("Accept", "text/html").header("Accept-Language", language);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
