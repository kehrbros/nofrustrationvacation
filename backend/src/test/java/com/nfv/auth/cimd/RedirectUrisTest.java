package com.nfv.auth.cimd;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedirectUrisTest {

    @Test
    void loopbackMatchesAnyPort() {
        List<String> registered = List.of("http://127.0.0.1/callback", "http://localhost/callback", "https://app.example/cb");
        assertTrue(RedirectUris.matches(registered, "http://127.0.0.1:53682/callback"));
        assertTrue(RedirectUris.matches(registered, "http://localhost:41234/callback"));
        assertTrue(RedirectUris.matches(registered, "https://app.example/cb"));
        assertFalse(RedirectUris.matches(registered, "http://127.0.0.1:53682/other"));
        assertFalse(RedirectUris.matches(registered, "http://127.0.0.1:53682/callback?x=1"));
        assertFalse(RedirectUris.matches(registered, "http://[::1]:53682/callback"));
        assertFalse(RedirectUris.matches(registered, "https://app.example:8443/cb"));
        assertFalse(RedirectUris.matches(registered, "https://app.example/cb/x"));
        assertFalse(RedirectUris.matches(registered, "http://evil.example:80/callback"));
    }

    @Test
    void privateUseSchemeMustBelongToClientHost() {
        assertTrue(RedirectUris.isPrivateUseScheme(URI.create("ai.claude:/callback"), "claude.ai"));
        assertTrue(RedirectUris.isPrivateUseScheme(URI.create("ai.claude.desktop:/callback"), "claude.ai"));
        assertFalse(RedirectUris.isPrivateUseScheme(URI.create("com.evil:/callback"), "claude.ai"));
        assertFalse(RedirectUris.isPrivateUseScheme(URI.create("ai.claudex:/callback"), "claude.ai"));
        assertFalse(RedirectUris.isPrivateUseScheme(URI.create("myapp:/callback"), "claude.ai"));
    }

    @Test
    void loopbackIsHttpOnly() {
        assertTrue(RedirectUris.isLoopback(URI.create("http://127.0.0.1:1234/cb")));
        assertTrue(RedirectUris.isLoopback(URI.create("http://[::1]/cb")));
        assertFalse(RedirectUris.isLoopback(URI.create("https://localhost/cb")));
        assertFalse(RedirectUris.isLoopback(URI.create("http://localhost.evil.example/cb")));
    }
}
