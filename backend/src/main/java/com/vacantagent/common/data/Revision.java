package com.vacantagent.common.data;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.envers.RevisionEntity;
import org.hibernate.envers.RevisionNumber;
import org.hibernate.envers.RevisionTimestamp;

import java.time.Instant;

/**
 * Revision for all entities marked with {@code @Audited}. Each transaction that changes audited
 * entities creates one row in {@code revinfo}; the {@code <table>_aud} tables reference it via {@code rev}.
 */
@Entity
@RevisionEntity
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

    protected Revision() {
    }

    public long getId() {
        return id;
    }

    public Instant getTimestamp() {
        return Instant.ofEpochMilli(timestamp);
    }
}
