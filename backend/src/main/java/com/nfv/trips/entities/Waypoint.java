package com.nfv.trips.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import org.hibernate.envers.Audited;
import org.hibernate.envers.RelationTargetAuditMode;

/** Point the user wants to pass between the two stops of a {@link Route}, without staying. Ordered by position. */
@Getter
@Entity
@Audited
public class Waypoint extends AbstractEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    // Route itself is not historized (planned, replaced)
    @Audited(targetAuditMode = RelationTargetAuditMode.NOT_AUDITED)
    private Route route;
    @Column(nullable = false)
    private int position;
    /** Coordinate chosen by the user, snapped to a road again on every planning. */
    @Column(nullable = false)
    private double latitude;
    @Column(nullable = false)
    private double longitude;
    private @Nullable String name;
    /** Road the point should snap to, e.g. {@code SH 73}. */
    private @Nullable String roadHint;

    protected Waypoint() {
    }

    public Waypoint(Route route, int position, double latitude, double longitude, @Nullable String name,
                    @Nullable String roadHint) {
        this.route = route;
        this.position = position;
        this.latitude = latitude;
        this.longitude = longitude;
        this.name = name;
        this.roadHint = roadHint;
    }

    public void moveTo(int position) {
        this.position = position;
    }

    public void change(double latitude, double longitude, @Nullable String name, @Nullable String roadHint) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.name = name;
        this.roadHint = roadHint;
    }
}