package com.nfv.auth.logic;

import com.nfv.auth.cimd.ClientRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Applications a user has connected to the account (MCP clients): authorizations grouped by client. Revoking deletes
 * all tokens and the consent of the client, the next access token request fails and the client must ask again.
 */
public class ConnectionLogic {
    private final JdbcTemplate jdbcTemplate;
    private final ClientRegistry clientRegistry;
    private final OAuth2AuthorizationConsentService consentService;

    public ConnectionLogic(JdbcTemplate jdbcTemplate, ClientRegistry clientRegistry,
                           OAuth2AuthorizationConsentService consentService) {
        this.jdbcTemplate = jdbcTemplate;
        this.clientRegistry = clientRegistry;
        this.consentService = consentService;
    }

    public List<Connection> connections(String username) {
        List<Row> rows = jdbcTemplate.query("SELECT registered_client_id, authorized_scopes, access_token_issued_at "
                        + "FROM oauth2_authorization WHERE principal_name = ? "
                        + "AND access_token_value IS NOT NULL",
                (rs, row) -> new Row(rs.getString("registered_client_id"), rs.getString("authorized_scopes"),
                        instant(rs.getTimestamp("access_token_issued_at"))),
                username);
        return rows.stream()
                .map(Row::registeredClientId)
                .distinct()
                .map(id -> toConnection(id, rows))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Connection::lastIssuedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    @Transactional
    public void revoke(String username, String registeredClientId) {
        jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE principal_name = ? AND registered_client_id = ?",
                username, registeredClientId);
        OAuth2AuthorizationConsent consent = consentService.findById(registeredClientId, username);
        if (consent != null) {
            consentService.remove(consent);
        }
    }

    private Connection toConnection(String registeredClientId, List<Row> rows) {
        RegisteredClient client = clientRegistry.findById(registeredClientId);
        // the frontend is not a connection the user manages here
        if (client == null || !ClientRegistry.isCimd(client)) {
            return null;
        }
        Set<String> scopes = new TreeSet<>();
        Instant lastIssuedAt = null;
        for (Row row : rows) {
            if (!row.registeredClientId().equals(registeredClientId)) {
                continue;
            }
            if (row.scopes() != null) {
                Arrays.stream(row.scopes().split(",")).map(String::trim).filter(s -> !s.isEmpty()).forEach(scopes::add);
            }
            if (row.issuedAt() != null && (lastIssuedAt == null || row.issuedAt().isAfter(lastIssuedAt))) {
                lastIssuedAt = row.issuedAt();
            }
        }
        return new Connection(registeredClientId, client.getClientName(), client.getClientId(), List.copyOf(scopes),
                lastIssuedAt);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record Row(String registeredClientId, String scopes, Instant issuedAt) {
    }

    public record Connection(String registeredClientId, String clientName, String clientId, List<String> scopes,
                             Instant lastIssuedAt) {
    }
}
