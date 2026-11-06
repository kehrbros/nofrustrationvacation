package com.nfv.input.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * What the model answered for one text and validity of one event at a source, so the same is not asked twice, see
 * Docs/traffic-feed.md#anreicherung-messageenrichment. Stored as answered, checked on every use.
 */
@Getter
@Entity
@Table(name = "message_enrichment")
public class StoredEnrichment extends AbstractEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private EventSource source;
    @Column(nullable = false)
    private String subjectId;
    @Column(nullable = false, length = 64)
    private String messageHash;
    /** Validity the model was given, its dated windows depend on it. */
    @Column(nullable = false, length = 100)
    private String validity;
    /** The answer as JSON. */
    @Column(nullable = false, length = 100_000)
    private String result;
    @Column(length = 100)
    private @Nullable String model;
    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected StoredEnrichment() {
    }

    public StoredEnrichment(EventSource source, String subjectId, String messageHash, String validity, String result,
                            @Nullable String model, OffsetDateTime createdAt) {
        this.source = source;
        this.subjectId = subjectId;
        this.messageHash = messageHash;
        this.validity = validity;
        this.result = result;
        this.model = model;
        this.createdAt = createdAt;
    }
}
