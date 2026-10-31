package com.vacantagent.trips.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * One step of a {@link Route} over exactly one segment of the road network. The segment is referenced by id only,
 * the network is a module of its own. The index on {@code segment_id} is the index segment → route section that
 * finds the routes affected by an incident, see Docs/domain.md.
 */
@Getter
@Entity
public class RouteSection extends AbstractEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Route route;
    /** Order within the route, starting at 0. */
    @Column(nullable = false)
    private int position;
    @Column(nullable = false)
    private long segmentId;
    /** Driven part of the segment from 0 (its start) to 1 (its end), below 0..1 only at the stops. */
    @Column(nullable = false)
    private double fromFraction;
    @Column(nullable = false)
    private double toFraction;
    /** Expected passage of the segment: entering it. */
    @Column(nullable = false)
    private OffsetDateTime expectedPassageStart;
    /** Expected passage of the segment: leaving it. */
    @Column(nullable = false)
    private OffsetDateTime expectedPassageEnd;

    protected RouteSection() {
    }

    public RouteSection(Route route, int position, long segmentId, double fromFraction, double toFraction,
                        OffsetDateTime expectedPassageStart, OffsetDateTime expectedPassageEnd) {
        if (expectedPassageEnd.isBefore(expectedPassageStart)) {
            throw new IllegalArgumentException("passage ends before it starts");
        }
        if (fromFraction < 0 || toFraction > 1 || fromFraction > toFraction) {
            throw new IllegalArgumentException("fractions must be 0 <= from <= to <= 1");
        }
        this.route = route;
        this.position = position;
        this.segmentId = segmentId;
        this.fromFraction = fromFraction;
        this.toFraction = toFraction;
        this.expectedPassageStart = expectedPassageStart;
        this.expectedPassageEnd = expectedPassageEnd;
    }
}
