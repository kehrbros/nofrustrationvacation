package com.nfv.incidents.entities;

import com.nfv.incidents.logic.TimeWindow;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.Objects;

/** Window of an {@link Incident}, stored in {@code incident_window}, see Docs/traffic-feed.md#zeitfenster. */
@Getter
@Embeddable
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

    /**
     * Same points in time, whatever the offset: the database hands them back in another offset than a source gives
     * them. Otherwise an unchanged window would count as removed and added, which collides in the history.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof IncidentWindow window && startsAt.isEqual(window.startsAt)
                && endsAt.isEqual(window.endsAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(startsAt.toInstant(), endsAt.toInstant());
    }
}
