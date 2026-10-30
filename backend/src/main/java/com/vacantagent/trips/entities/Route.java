package com.vacantagent.trips.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * Way between exactly two {@link Stop}s of a {@link Trip}. Belongs to this trip only, even if another trip takes the
 * same way. The way itself is the ordered list of {@link RouteSection}s.
 */
@Getter
@Entity
public class Route extends AbstractEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Trip trip;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Stop fromStop;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Stop toStop;
    /** When the sections were last (re)planned, {@code null} until then. */
    private @Nullable OffsetDateTime plannedAt;

    protected Route() {
    }

    public Route(Trip trip, Stop fromStop, Stop toStop) {
        this.trip = trip;
        this.fromStop = fromStop;
        this.toStop = toStop;
    }

    public void planned(OffsetDateTime plannedAt) {
        this.plannedAt = plannedAt;
    }
}
