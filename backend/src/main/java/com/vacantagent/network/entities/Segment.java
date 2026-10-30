package com.vacantagent.network.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

/**
 * Part of a {@link Road} from one junction to the next. Exists exactly once in the network, routes and incidents only
 * refer to it, see Docs/domain.md. Direction matters: the opposite carriageway is a segment of its own.
 */
@Getter
@Entity
public class Segment extends AbstractEntity {
    public static final int MAX_GEOMETRY_LENGTH = 1_000_000;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Road road;
    /** Junction ("Anschlussstelle") the segment starts at. */
    @Column(name = "from_junction", nullable = false)
    private String from;
    /** Junction the segment ends at. */
    @Column(name = "to_junction", nullable = false)
    private String to;
    /** GeoJSON LineString in WGS84 from {@link #from} to {@link #to}. */
    @Column(nullable = false, length = MAX_GEOMETRY_LENGTH)
    private String geometry;

    protected Segment() {
    }

    public Segment(Road road, String from, String to, String geometry) {
        this.road = road;
        this.from = from;
        this.to = to;
        this.geometry = geometry;
    }
}
