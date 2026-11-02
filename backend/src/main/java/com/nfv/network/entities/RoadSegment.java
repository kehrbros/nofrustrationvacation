package com.nfv.network.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import org.locationtech.jts.geom.LineString;

import java.time.OffsetDateTime;

/**
 * One drivable direction of a road from junction to junction, created from OpenStreetMap by the GraphHopper import.
 * The id stays stable across imports, routes and incidents refer to it, see Docs/network.md.
 */
@Getter
@Entity
public class RoadSegment extends AbstractEntity {
    @Column(nullable = false, length = 16)
    private String region;
    @Column(nullable = false)
    private long osmWayId;
    /** Junction coordinates in 1e-7 degrees, part of the business key. */
    @Column(nullable = false)
    private int fromLat;
    @Column(nullable = false)
    private int fromLon;
    @Column(nullable = false)
    private int toLat;
    @Column(nullable = false)
    private int toLon;
    /** Hash of the geometry, tells apart edges of the same way between the same junctions. */
    @Column(nullable = false)
    private int geometryHash;
    /** Road number, e.g. {@code SH 1}. */
    @Nullable
    private String ref;
    @Nullable
    private String name;
    /** GraphHopper road class, e.g. {@code MOTORWAY}. */
    @Column(nullable = false, length = 32)
    private String roadClass;
    @Column(nullable = false)
    private boolean bridge;
    @Column(nullable = false)
    private boolean tunnel;
    @Column(name = "length_m", nullable = false)
    private double lengthMeters;
    /** WGS84 (SRID 4326), in driving direction. */
    @Column(nullable = false, columnDefinition = "geometry(LineString, 4326)")
    private LineString geometry;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private ExtractVersion firstSeenVersion;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private ExtractVersion lastSeenVersion;
    /** Set when the segment is no longer in the current extract. */
    @Nullable
    private OffsetDateTime retiredAt;

    protected RoadSegment() {
    }

    public RoadSegment(String region, long osmWayId, int fromLat, int fromLon, int toLat, int toLon, int geometryHash,
                       @Nullable String ref, @Nullable String name, String roadClass, boolean bridge, boolean tunnel,
                       double lengthMeters, LineString geometry, ExtractVersion version) {
        this.region = region;
        this.osmWayId = osmWayId;
        this.fromLat = fromLat;
        this.fromLon = fromLon;
        this.toLat = toLat;
        this.toLon = toLon;
        this.geometryHash = geometryHash;
        this.ref = ref;
        this.name = name;
        this.roadClass = roadClass;
        this.bridge = bridge;
        this.tunnel = tunnel;
        this.lengthMeters = lengthMeters;
        this.geometry = geometry;
        this.firstSeenVersion = version;
        this.lastSeenVersion = version;
    }
}