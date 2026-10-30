package com.vacantagent.input.entities;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import lombok.Getter;

import java.time.OffsetDateTime;

/** Traffic message (road closure, congestion, roadworks) with the fields already read from the payload. */
@Getter
@Entity
@DiscriminatorValue("TRAFFIC")
public class TrafficEvent extends Event {
    /** Road number as the source names it, e.g. {@code A6}. */
    private @Nullable String roadNumber;
    @Column(length = 2000)
    private @Nullable String description;
    private @Nullable OffsetDateTime startsAt;
    private @Nullable OffsetDateTime endsAt;

    protected TrafficEvent() {
    }

    public TrafficEvent(EventSource source, @Nullable String externalId, String payload, OffsetDateTime receivedAt,
                        @Nullable String roadNumber, @Nullable String description, @Nullable OffsetDateTime startsAt,
                        @Nullable OffsetDateTime endsAt) {
        super(source, externalId, payload, receivedAt);
        this.roadNumber = roadNumber;
        this.description = description;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }
}
