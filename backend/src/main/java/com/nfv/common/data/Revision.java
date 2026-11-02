package com.nfv.common.data;

import jakarta.persistence.*;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

import java.time.Instant;

/**
 * Revision for all entities marked with {@code @Audited}. Each transaction that changes audited
 * entities creates one row in {@code revinfo}; the {@code <table>_aud} tables reference it via {@code rev}.
 * {@link #username} is the authenticated user of the change, {@code null} for changes without user context.
 */
@Entity
@RevisionEntity(UsernameRevisionListener.class)
@Table(name = "revinfo")
public class Revision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @RevisionNumber
    @Column(name = "rev")
    private long id;

    @RevisionTimestamp
    @Column(name = "revtstmp", nullable = false)
    private long timestamp;

    @Column(name = "username")
    private String username;

    protected Revision() {
    }

    public long getId() {
        return id;
    }

    public Instant getTimestamp() {
        return Instant.ofEpochMilli(timestamp);
    }

    public String getUsername() {
        return username;
    }

    void setUsername(String username) {
        this.username = username;
    }
}
