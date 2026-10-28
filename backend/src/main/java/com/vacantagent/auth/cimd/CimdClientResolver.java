package com.vacantagent.auth.cimd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a {@code client_id} URL into a {@link RegisteredClient}: allowlist, fetch (or cache), validation. Unknown or
 * invalid clients resolve to null, which the authorization server answers with {@code invalid_client}.
 */
public class CimdClientResolver {
    /**
     * Client setting marking clients that come from a metadata document.
     */
    public static final String SETTING_CIMD = "vacantagent.cimd";
    /**
     * Client setting marking native clients (only loopback or private-use scheme redirect URIs).
     */
    public static final String SETTING_NATIVE = "vacantagent.cimd.native";
    private static final String ID_PREFIX = "cimd-";
    private static final Logger LOG = LoggerFactory.getLogger(CimdClientResolver.class);

    private final CimdProperties properties;
    private final ClientMetadataSource source;
    private final CimdClientStore store;
    private final Clock clock;
    private final Map<String, Object> fetchLocks = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> fetchesByHost = new ConcurrentHashMap<>();

    public CimdClientResolver(CimdProperties properties, ClientMetadataSource source, CimdClientStore store, Clock clock) {
        this.properties = properties;
        this.source = source;
        this.store = store;
        this.clock = clock;
    }

    /**
     * Stable id of the registered client, referenced by stored authorizations and consents.
     */
    public static String registeredClientId(String clientId) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(clientId.getBytes(StandardCharsets.UTF_8));
            return ID_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public RegisteredClient findByClientId(String clientId) {
        if (!properties.enabled() || !ClientMetadataValidator.isUrl(clientId)) {
            return null;
        }
        URI uri;
        try {
            uri = ClientMetadataValidator.validateClientId(clientId);
        } catch (CimdException e) {
            LOG.info("Rejected client_id: {}", e.getMessage());
            return null;
        }
        if (!properties.isAllowed(clientId)) {
            LOG.info("Rejected client_id {}: not on the allowlist (vacantagent.auth.cimd.allowed-client-ids)", clientId);
            return null;
        }
        String id = registeredClientId(clientId);
        Optional<CimdClientStore.Stored> cached = store.find(id).filter(this::isFresh);
        if (cached.isPresent()) {
            return toRegisteredClient(id, ClientMetadataValidator.parse(clientId, cached.get().json()));
        }
        synchronized (fetchLocks.computeIfAbsent(id, key -> new Object())) {
            Optional<CimdClientStore.Stored> stored = store.find(id);
            if (stored.isPresent() && isFresh(stored.get())) {
                return toRegisteredClient(id, ClientMetadataValidator.parse(clientId, stored.get().json()));
            }
            try {
                return toRegisteredClient(id, fetch(id, uri));
            } catch (CimdException e) {
                Instant now = clock.instant();
                if (stored.isPresent() && stored.get().expiresAt().plus(properties.staleGrace()).isAfter(now)) {
                    LOG.warn("Fetching metadata document {} failed, using the cached one: {}", clientId, e.getMessage());
                    return toRegisteredClient(id, ClientMetadataValidator.parse(clientId, stored.get().json()));
                }
                LOG.warn("Rejected client_id {}: {}", clientId, e.getMessage());
                return null;
            }
        }
    }

    /**
     * Lookup by registered client id for stored authorizations, also with an expired document.
     */
    public RegisteredClient findById(String id) {
        if (id == null || !id.startsWith(ID_PREFIX)) {
            return null;
        }
        return store.find(id)
                .map(stored -> toRegisteredClient(id, ClientMetadataValidator.parse(stored.clientId(), stored.json())))
                .orElse(null);
    }

    private ClientMetadata fetch(String id, URI uri) {
        String clientId = uri.toString();
        acquireFetch(uri.getHost());
        ClientMetadataSource.Document document = source.fetch(uri);
        ClientMetadata metadata = ClientMetadataValidator.parse(clientId, document.json());
        Instant now = clock.instant();
        store.save(id, clientId, document.json(), now, now.plus(ttl(document.maxAge())));
        LOG.info("Fetched metadata document of client {} ({})", clientId, metadata.clientName());
        return metadata;
    }

    private Duration ttl(Duration maxAge) {
        Duration ttl = maxAge == null ? properties.cacheDefaultTtl() : maxAge;
        if (ttl.compareTo(properties.cacheMinTtl()) < 0) {
            return properties.cacheMinTtl();
        }
        return ttl.compareTo(properties.cacheMaxTtl()) > 0 ? properties.cacheMaxTtl() : ttl;
    }

    private boolean isFresh(CimdClientStore.Stored stored) {
        return stored.expiresAt().isAfter(clock.instant());
    }

    /**
     * At most {@code maxFetchesPerMinute} fetches per host, so the IdP cannot be used to flood a host and an attacker
     * cannot keep it busy with ever new URLs.
     */
    private void acquireFetch(String host) {
        Instant now = clock.instant();
        Deque<Instant> fetches = fetchesByHost.computeIfAbsent(host, key -> new ArrayDeque<>());
        synchronized (fetches) {
            while (!fetches.isEmpty() && fetches.peekFirst().isBefore(now.minus(Duration.ofMinutes(1)))) {
                fetches.pollFirst();
            }
            if (fetches.size() >= properties.maxFetchesPerMinute()) {
                throw new CimdException("too many fetches from " + host);
            }
            fetches.addLast(now);
        }
    }

    private RegisteredClient toRegisteredClient(String id, ClientMetadata metadata) {
        boolean nativeClient = metadata.isNative();
        return RegisteredClient.withId(id)
                .clientId(metadata.clientId())
                .clientName(metadata.clientName())
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUris(uris -> uris.addAll(metadata.redirectUris()))
                .scopes(scopes -> scopes.addAll(nativeClient ? properties.nativeScopes() : properties.scopes()))
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(true)
                        .setting(SETTING_CIMD, true)
                        .setting(SETTING_NATIVE, nativeClient)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(properties.accessTokenTtl())
                        .refreshTokenTimeToLive(properties.refreshTokenTtl())
                        .reuseRefreshTokens(false)
                        .build())
                .build();
    }
}
