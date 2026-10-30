package com.vacantagent.incidents.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Effect of an {@link Incident} on exactly one route of a trip, at most one per incident and route. Points to the
 * first affected section of the route. Derived by {@code ConsequenceMatcher}, never edited by users. Route and
 * section are referenced by id (trips module); the database removes the consequence with them.
 */
@Getter
@Entity
public class Consequence extends AbstractEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Incident incident;
    @Column(nullable = false)
    private long routeId;
    @Column(nullable = false)
    private long routeSectionId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Impact impact;
    private @Nullable Duration delay;
    @Column(nullable = false)
    private OffsetDateTime detectedAt;
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected Consequence() {
    }

    public Consequence(Incident incident, long routeId, long routeSectionId, Impact impact, @Nullable Duration delay,
                       OffsetDateTime detectedAt) {
        this.incident = incident;
        this.routeId = routeId;
        this.detectedAt = detectedAt;
        update(routeSectionId, impact, delay, detectedAt);
    }

    public void update(long routeSectionId, Impact impact, @Nullable Duration delay, OffsetDateTime updatedAt) {
        this.routeSectionId = routeSectionId;
        this.impact = impact;
        this.delay = delay;
        this.updatedAt = updatedAt;
    }
}
