package com.nfv.incidents.entities;

import com.nfv.incidents.logic.TimeWindow;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.time.OffsetDateTime;

/** Window of an {@link Incident}, stored in {@code incident_window}, see Docs/traffic-feed.md#zeitfenster. */
@Getter
@Embeddable
@EqualsAndHashCode
public class IncidentWindow {
    @Column(nullable = false)
    private OffsetDateTime startsAt;
    @Column(nullable = false)
    private OffsetDateTime endsAt;

    protected IncidentWindow() {
    }

    public IncidentWindow(TimeWindow window) {
        this.startsAt = window.startsAt();
        this.endsAt = window.endsAt();
    }

    public TimeWindow toTimeWindow() {
        return new TimeWindow(startsAt, endsAt);
    }
}
