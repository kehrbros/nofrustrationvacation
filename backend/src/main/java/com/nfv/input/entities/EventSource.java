package com.nfv.input.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;

import java.time.OffsetDateTime;

/** Origin of {@link Event}s, e.g. a traffic information service. */
@Getter
@Entity
public class EventSource extends AbstractEntity {
    @Column(nullable = false, unique = true, length = 64)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventSourceType type;
    private boolean enabled;
    /** Last successful poll (also when the source answered "not modified"), shown as "Stand" in the frontend. */
    private @Nullable OffsetDateTime lastSuccessAt;
    /** {@code ETag} of the last response, sent as {@code If-None-Match}. */
    private @Nullable String etag;
    /** {@code Last-Modified} of the last response as received, sent as {@code If-Modified-Since}. */
    @Column(length = 64)
    private @Nullable String lastModified;

    protected EventSource() {
    }

    public EventSource(String name, EventSourceType type) {
        this.name = name;
        this.type = type;
        this.enabled = true;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** A poll succeeded. Validators are only replaced if the response carried them (not on "not modified"). */
    public void polled(OffsetDateTime at, @Nullable String etag, @Nullable String lastModified, boolean notModified) {
        this.lastSuccessAt = at;
        if (!notModified) {
            this.etag = etag;
            this.lastModified = lastModified;
        }
    }
}
