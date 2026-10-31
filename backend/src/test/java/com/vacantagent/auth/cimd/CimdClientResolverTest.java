package com.vacantagent.auth.cimd;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CimdClientResolverTest {
    private static final String WEB = "https://client.example/oauth/metadata.json";
    private static final String CLI = "https://client.example/cli/metadata.json";

    private final MutableClock clock = new MutableClock();
    private final MemoryStore store = new MemoryStore();
    private final Map<String, String> documents = new HashMap<>();
    private final AtomicInteger fetches = new AtomicInteger();
    private boolean hostDown;
    private Duration maxAge;
    private CimdClientResolver resolver;

    @BeforeEach
    void setUp() {
        documents.put(WEB, document(WEB, "Web", "https://client.example/cb"));
        documents.put(CLI, document(CLI, "CLI", "http://127.0.0.1/cb"));
        CimdProperties properties = new CimdProperties(true, List.of(WEB, "https://client.example/cli/"),
                List.of("mcp:read", "mcp:write"), List.of("mcp:read"), Duration.ofSeconds(3), 5120, 3,
                Duration.ofMinutes(5), Duration.ofHours(24), Duration.ofHours(1), Duration.ofHours(24),
                Duration.ofMinutes(15), Duration.ofDays(30));
        ClientMetadataSource source = uri -> {
            fetches.incrementAndGet();
            if (hostDown) {
                throw new CimdException("connection refused");
            }
            return new ClientMetadataSource.Document(documents.get(uri.toString()), maxAge);
        };
        resolver = new CimdClientResolver(properties, source, store, clock);
    }

    @Test
    void resolvesAllowedClient() {
        RegisteredClient client = resolver.findByClientId(WEB);
        assertNotNull(client);
        assertEquals(CimdClientResolver.registeredClientId(WEB), client.getId());
        assertEquals("Web", client.getClientName());
        assertEquals(Set.of("mcp:read", "mcp:write"), client.getScopes());
        assertTrue(ClientRegistry.isCimd(client));
        assertFalse(ClientRegistry.isNative(client));
        assertTrue(client.getClientSettings().isRequireProofKey());
        assertTrue(client.getClientSettings().isRequireAuthorizationConsent());
        assertFalse(client.getTokenSettings().isReuseRefreshTokens());
    }

    @Test
    void nativeClientGetsNativeScopes() {
        RegisteredClient client = resolver.findByClientId(CLI);
        assertNotNull(client);
        assertTrue(ClientRegistry.isNative(client));
        assertEquals(Set.of("mcp:read"), client.getScopes());
    }

    @Test
    void neverFetchesClientsOutsideTheAllowlist() {
        assertNull(resolver.findByClientId("https://evil.example/metadata.json"));
        assertNull(resolver.findByClientId("https://client.example/oauth/other.json"));
        assertNull(resolver.findByClientId("vacantagent-app"));
        assertEquals(0, fetches.get());
    }

    @Test
    void cachesDocumentsWithinTheirLifetime() {
        maxAge = Duration.ofSeconds(10);
        resolver.findByClientId(WEB);
        resolver.findByClientId(WEB);
        assertEquals(1, fetches.get());

        clock.advance(Duration.ofMinutes(4));
        resolver.findByClientId(WEB);
        assertEquals(1, fetches.get(), "max-age below the minimum TTL");

        clock.advance(Duration.ofMinutes(2));
        resolver.findByClientId(WEB);
        assertEquals(2, fetches.get());
    }

    @Test
    void usesStaleDocumentWhileHostIsDownWithinGrace() {
        resolver.findByClientId(WEB);
        hostDown = true;
        clock.advance(Duration.ofHours(2));
        assertNotNull(resolver.findByClientId(WEB));

        clock.advance(Duration.ofHours(24));
        assertNull(resolver.findByClientId(WEB));
        assertNotNull(resolver.findById(CimdClientResolver.registeredClientId(WEB)), "stored authorizations still resolve");
    }

    @Test
    void invalidDocumentIsRejected() {
        documents.put(WEB, document("https://other.example/metadata.json", "Web", "https://client.example/cb"));
        assertNull(resolver.findByClientId(WEB));
    }

    @Test
    void limitsFetchesPerHost() {
        maxAge = Duration.ZERO;
        for (int i = 0; i < 3; i++) {
            store.clear();
            assertNotNull(resolver.findByClientId(WEB));
        }
        store.clear();
        assertNull(resolver.findByClientId(WEB));
        clock.advance(Duration.ofMinutes(2));
        assertNotNull(resolver.findByClientId(WEB));
    }

    private static String document(String clientId, String name, String redirectUri) {
        return "{\"client_id\":\"" + clientId + "\",\"client_name\":\"" + name + "\",\"redirect_uris\":[\""
                + redirectUri + "\"]}";
    }

    private static final class MemoryStore extends CimdClientStore {
        private final Map<String, Stored> rows = new HashMap<>();

        MemoryStore() {
            super(null);
        }

        @Override
        public Optional<Stored> find(String registeredClientId) {
            return Optional.ofNullable(rows.get(registeredClientId));
        }

        @Override
        public void save(String registeredClientId, String clientId, String json, Instant fetchedAt, Instant expiresAt) {
            rows.put(registeredClientId, new Stored(clientId, json, fetchedAt, expiresAt));
        }

        void clear() {
            rows.clear();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-28T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
