package com.nfv.recommendations.entities;

import com.nfv.common.data.AbstractEntity;
import com.nfv.incidents.entities.Impact;
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

/** Incident that still affects a {@link RouteOption} at its passage, referenced by id (incidents module). */
@Getter
@Entity
public class RouteOptionConflict extends AbstractEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private RouteOption routeOption;
    @Column(nullable = false)
    private long incidentId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Impact impact;
    private @Nullable Duration delay;

    protected RouteOptionConflict() {
    }

    RouteOptionConflict(RouteOption routeOption, long incidentId, Impact impact, @Nullable Duration delay) {
        this.routeOption = routeOption;
        this.incidentId = incidentId;
        this.impact = impact;
        this.delay = delay;
    }
}