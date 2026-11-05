package com.nfv.input.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * Last known version of one event of a polled {@link EventSource} (subject), the state of the feed comparison, see
 * Docs/traffic-feed.md#abgleich-feeddiff.
 */
@Getter
@Entity
public class SourceSubject extends AbstractEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private EventSource source;
    /** Id of the event at the source. */
    @Column(nullable = false)
    private String subjectId;
    /** Version of the event at the source (modification time or counter), compared as text. */
    @Column(nullable = false)
    private String version;
    @Column(length = 64)
    private @Nullable String messageHash;
    /** Successive complete polls without the event. */
    private int missingPolls;
    @Column(nullable = false)
    private OffsetDateTime lastSeenAt;
    /** Set once the event was cleared after missing from the feed, reset if it comes back. */
    private @Nullable OffsetDateTime clearedAt;

    protected SourceSubject() {
    }

    public SourceSubject(EventSource source, String subjectId, String version, @Nullable String messageHash,
                         OffsetDateTime seenAt) {
        this.source = source;
        this.subjectId = subjectId;
        this.version = version;
        this.messageHash = messageHash;
        this.lastSeenAt = seenAt;
    }

    public boolean isCleared() {
        return clearedAt != null;
    }

    /** The event is in the feed again, possibly in a new version. */
    public void seen(String version, @Nullable String messageHash, OffsetDateTime at) {
        this.version = version;
        this.messageHash = messageHash;
        this.lastSeenAt = at;
        this.missingPolls = 0;
        this.clearedAt = null;
    }

    /** @return number of successive complete polls without the event, including this one */
    public int missing() {
        return ++missingPolls;
    }

    public void cleared(OffsetDateTime at) {
        this.clearedAt = at;
    }
}
