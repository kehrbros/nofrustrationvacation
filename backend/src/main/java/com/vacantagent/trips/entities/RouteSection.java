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
    /** Expected passage of the segment: entering it. */
    @Column(nullable = false)
    private OffsetDateTime expectedPassageStart;
    /** Expected passage of the segment: leaving it. */
    @Column(nullable = false)
    private OffsetDateTime expectedPassageEnd;

    protected RouteSection() {
    }

    public RouteSection(Route route, int position, long segmentId, OffsetDateTime expectedPassageStart,
                        OffsetDateTime expectedPassageEnd) {
        if (expectedPassageEnd.isBefore(expectedPassageStart)) {
            throw new IllegalArgumentException("passage ends before it starts");
        }
        this.route = route;
        this.position = position;
        this.segmentId = segmentId;
        this.expectedPassageStart = expectedPassageStart;
        this.expectedPassageEnd = expectedPassageEnd;
    }
}
