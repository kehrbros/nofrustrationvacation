package com.nfv.input.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorColumn;
import jakarta.persistence.DiscriminatorType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Inheritance;
import jakarta.persistence.InheritanceType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * Raw message received from an {@link EventSource}, kept as received. Processing may turn it into at most one
 * incident ({@link #incidentId}); several events (e.g. updates of a road closure) may lead to the same incident.
 * One table for all subtypes, {@code kind} tells them apart.
 */
@Getter
@Entity
@Table(name = "input_event")
@Inheritance(strategy = InheritanceType.SINGLE_TABLE)
@DiscriminatorColumn(name = "kind", discriminatorType = DiscriminatorType.STRING, length = 16)
public abstract class Event extends AbstractEntity {
    public static final int MAX_PAYLOAD_LENGTH = 1_000_000;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private EventSource source;
    /** Id of the message at the source, unique per source, so a message redelivered is recognised. */
    private @Nullable String externalId;
    /** Id of the event at the source, the same for all versions of it (see {@link SourceSubject}). */
    private @Nullable String subjectId;
    /** SHA-256 of the normalised message text, {@code null} without text, see Docs/traffic-feed.md#anreicherung-messageenrichment. */
    @Column(length = 64)
    private @Nullable String messageHash;
    @Column(nullable = false, length = MAX_PAYLOAD_LENGTH)
    private String payload;
    @Column(nullable = false)
    private OffsetDateTime receivedAt;
    private @Nullable OffsetDateTime processedAt;
    private @Nullable Long incidentId;
    /** Why processing led to no incident. */
    @Column(length = 500)
    private @Nullable String processingNote;
    /** Processed without enrichment, enrichment is caught up later. */
    private boolean enrichmentPending;

    protected Event() {
    }

    protected Event(EventSource source, @Nullable String externalId, String payload, OffsetDateTime receivedAt) {
        this(source, externalId, null, null, payload, receivedAt);
    }

    protected Event(EventSource source, @Nullable String externalId, @Nullable String subjectId,
                    @Nullable String messageHash, String payload, OffsetDateTime receivedAt) {
        this.source = source;
        this.externalId = externalId;
        this.subjectId = subjectId;
        this.messageHash = messageHash;
        this.payload = payload;
        this.receivedAt = receivedAt;
    }

    /** @param incidentId incident the event led to, {@code null} if it is irrelevant */
    public void processed(@Nullable Long incidentId, OffsetDateTime processedAt) {
        processed(incidentId, null, processedAt);
    }

    /**
     * @param incidentId incident the event led to, {@code null} if none
     * @param note       why there is no incident, or what was adjusted; at most 500 characters are kept
     */
    public void processed(@Nullable Long incidentId, @Nullable String note, OffsetDateTime processedAt) {
        this.incidentId = incidentId;
        this.processingNote = note == null || note.length() <= 500 ? note : note.substring(0, 500);
        this.processedAt = processedAt;
    }
}
