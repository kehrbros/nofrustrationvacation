package com.nfv.auth.cimd;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * OAuth Client ID Metadata Documents (CIMD) of MCP clients, see Docs/authentication.md.
 *
 * @param enabled          accept HTTPS URLs as {@code client_id}
 * @param allowedClientIds allowlist: exact {@code client_id} URLs or prefixes ending with {@code /}
 * @param scopes           scopes of clients with at least one HTTPS redirect URI
 * @param nativeScopes     scopes of clients with only loopback or private-use scheme redirect URIs. Any local
 *                         program can pose as such a client, so they get read access only.
 * @param fetchTimeout     connect and read timeout when fetching a document
 * @param maxDocumentSize  largest accepted document in bytes
 * @param maxFetchesPerMinute fetches per host and minute, protects the IdP and the hosts from abuse
 * @param cacheMinTtl      shortest time a fetched document is used, even if its {@code Cache-Control} says less
 * @param cacheMaxTtl      longest time a fetched document is used without fetching it again
 * @param cacheDefaultTtl  time a document is used without {@code Cache-Control: max-age}
 * @param staleGrace       time an expired document is still used while its host cannot be reached
 * @param accessTokenTtl   lifetime of access tokens of CIMD clients
 * @param refreshTokenTtl  lifetime of refresh tokens of CIMD clients, rotated on every use
 */
@ConfigurationProperties("nfv.auth.cimd")
public record CimdProperties(boolean enabled, List<String> allowedClientIds, List<String> scopes,
                             List<String> nativeScopes, Duration fetchTimeout, int maxDocumentSize,
                             int maxFetchesPerMinute, Duration cacheMinTtl, Duration cacheMaxTtl,
                             Duration cacheDefaultTtl, Duration staleGrace, Duration accessTokenTtl,
                             Duration refreshTokenTtl) {

    public CimdProperties {
        allowedClientIds = allowedClientIds == null ? List.of() : List.copyOf(allowedClientIds);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        nativeScopes = nativeScopes == null ? List.of() : List.copyOf(nativeScopes);
    }

    /**
     * Whether the {@code client_id} is on the allowlist.
     */
    public boolean isAllowed(String clientId) {
        return allowedClientIds.stream()
                .anyMatch(allowed -> allowed.endsWith("/") ? clientId.startsWith(allowed) : clientId.equals(allowed));
    }
}
