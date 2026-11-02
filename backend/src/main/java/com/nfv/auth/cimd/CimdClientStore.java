package com.nfv.auth.cimd;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Cache of fetched metadata documents in {@code auth_cimd_client}. Also the lookup by registered client id, which
 * stored authorizations refer to.
 */
public class CimdClientStore {
    private final JdbcTemplate jdbcTemplate;

    public CimdClientStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Stored> find(String registeredClientId) {
        return jdbcTemplate.query("SELECT client_id, metadata_json, fetched_at, expires_at FROM auth_cimd_client "
                                + "WHERE registered_client_id = ?",
                        (rs, row) -> new Stored(rs.getString("client_id"), rs.getString("metadata_json"),
                                rs.getTimestamp("fetched_at").toInstant(), rs.getTimestamp("expires_at").toInstant()),
                        registeredClientId)
                .stream().findFirst();
    }

    public void save(String registeredClientId, String clientId, String json, Instant fetchedAt, Instant expiresAt) {
        int updated = update(registeredClientId, json, fetchedAt, expiresAt);
        if (updated == 0) {
            try {
                jdbcTemplate.update("INSERT INTO auth_cimd_client (registered_client_id, client_id, metadata_json, "
                                + "fetched_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                        registeredClientId, clientId, json, Timestamp.from(fetchedAt), Timestamp.from(expiresAt));
            } catch (DuplicateKeyException concurrentlyInserted) {
                update(registeredClientId, json, fetchedAt, expiresAt);
            }
        }
    }

    private int update(String registeredClientId, String json, Instant fetchedAt, Instant expiresAt) {
        return jdbcTemplate.update("UPDATE auth_cimd_client SET metadata_json = ?, fetched_at = ?, expires_at = ? "
                        + "WHERE registered_client_id = ?",
                json, Timestamp.from(fetchedAt), Timestamp.from(expiresAt), registeredClientId);
    }

    public record Stored(String clientId, String json, Instant fetchedAt, Instant expiresAt) {
    }
}
