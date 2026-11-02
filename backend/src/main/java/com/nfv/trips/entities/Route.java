package com.nfv.trips.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
    /** Outcome of the last planning, {@code null} until planned or after a change of stops or waypoints. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private @Nullable PlanOutcome planOutcome;
    /** Why the last planning failed, {@code null} otherwise. */
    @Column(length = 500)
    private @Nullable String planReason;

    protected Route() {
    }

    public Route(Trip trip, Stop fromStop, Stop toStop) {
        this.trip = trip;
        this.fromStop = fromStop;
        this.toStop = toStop;
    }

    public void planned(OffsetDateTime plannedAt) {
        this.plannedAt = plannedAt;
        this.planOutcome = PlanOutcome.PLANNED;
        this.planReason = null;
    }

    /** Planning failed, the sections stay as they are (from the last successful planning or none). */
    public void planFailed(PlanOutcome outcome, @Nullable String reason) {
        this.planOutcome = outcome;
        this.planReason = reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    /** Stops or waypoints changed: the sections are gone until the route is planned again. */
    public void discarded() {
        this.plannedAt = null;
        this.planOutcome = null;
        this.planReason = null;
    }
}
