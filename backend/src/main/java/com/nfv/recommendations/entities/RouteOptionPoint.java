package com.nfv.recommendations.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

/**
 * Via point of a {@link RouteOption}, becomes a waypoint when the option is applied. The intention is stored, not the
 * segments: the points reproduce the way, see Docs/recommendations.md#übernehmen-eines-vorschlags.
 */
@Getter
@Entity
public class RouteOptionPoint extends AbstractEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private RouteOption routeOption;
    @Column(nullable = false)
    private int position;
    @Column(nullable = false)
    private double latitude;
    @Column(nullable = false)
    private double longitude;
    private @Nullable String roadHint;
    /** Existing waypoint of the user ({@code FOLLOW}), kept with its id when applied; {@code null}: new point. */
    private @Nullable Long waypointId;

    protected RouteOptionPoint() {
    }

    RouteOptionPoint(RouteOption routeOption, int position, double latitude, double longitude,
                     @Nullable String roadHint, @Nullable Long waypointId) {
        this.routeOption = routeOption;
        this.position = position;
        this.latitude = latitude;
        this.longitude = longitude;
        this.roadHint = roadHint;
        this.waypointId = waypointId;
    }
}