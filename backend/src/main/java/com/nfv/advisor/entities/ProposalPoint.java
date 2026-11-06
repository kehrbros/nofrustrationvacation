package com.nfv.advisor.entities;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;

/** Point that becomes a waypoint when the proposal is chosen, as {@code PUT /routes/{id}/choice} takes it. */
@Getter
@Embeddable
public class ProposalPoint {
    @Column(nullable = false)
    private double latitude;
    @Column(nullable = false)
    private double longitude;
    private @Nullable String roadHint;
    /** Existing waypoint of the user that stays, {@code null}: new point, or the waypoint is gone by now. */
    private @Nullable Long waypointId;

    protected ProposalPoint() {
    }

    public ProposalPoint(double latitude, double longitude, @Nullable String roadHint, @Nullable Long waypointId) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.roadHint = roadHint;
        this.waypointId = waypointId;
    }
}
