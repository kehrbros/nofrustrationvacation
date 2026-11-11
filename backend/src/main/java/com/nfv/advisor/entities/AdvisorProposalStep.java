package com.nfv.advisor.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A step of a plan as the backend computed it, see Docs/incident-plans.md#datenmodell. Which fields are set depends
 * on {@link #kind}.
 */
@Getter
@Entity
@Table(name = "advisor_proposal_step")
public class AdvisorProposalStep extends AbstractEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private AdvisorProposal proposal;
    @Column(nullable = false)
    private int position;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PlanStepKind kind;
    /** {@code DRIVE}: how, {@code REROUTE}, {@code SHIFT_DEPARTURE} or {@code KEEP_ROUTE}. */
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private @Nullable ProposalKind routeKind;
    private @Nullable String via;
    private @Nullable OffsetDateTime departureAt;
    private @Nullable OffsetDateTime arrivalAt;
    /** {@code CONTINUE}: departure later than usual because a closure ends then. */
    private @Nullable OffsetDateTime waitedFor;
    private @Nullable Double distanceMeters;
    private @Nullable Duration duration;
    /** {@code OVERNIGHT}, {@code NEW_TARGET}: the place, named after it. */
    private @Nullable String placeName;
    private @Nullable Double latitude;
    private @Nullable Double longitude;
    /** {@code KEEP_BOOKING}: the booked accommodation. */
    private @Nullable Long bookingId;
    /** {@code KEEP_BOOKING}: day of the new arrival. */
    private @Nullable LocalDate arrivalOn;
    /** Points that become the waypoints of the route of {@code DRIVE} or {@code CONTINUE}. */
    @ElementCollection
    @CollectionTable(name = "advisor_proposal_step_point", joinColumns = @JoinColumn(name = "step_id"))
    @OrderColumn(name = "position")
    private List<ProposalPoint> points = new ArrayList<>();
    @Embedded
    private @Nullable ProposalStay stay;

    protected AdvisorProposalStep() {
    }

    public AdvisorProposalStep(int position, PlanStepKind kind, @Nullable ProposalKind routeKind, @Nullable String via,
                               @Nullable OffsetDateTime departureAt, @Nullable OffsetDateTime arrivalAt,
                               @Nullable OffsetDateTime waitedFor, @Nullable Double distanceMeters,
                               @Nullable Duration duration, @Nullable String placeName, @Nullable Double latitude,
                               @Nullable Double longitude, @Nullable Long bookingId, @Nullable LocalDate arrivalOn,
                               List<ProposalPoint> points, @Nullable ProposalStay stay) {
        this.position = position;
        this.kind = kind;
        this.routeKind = routeKind;
        this.via = via;
        this.departureAt = departureAt;
        this.arrivalAt = arrivalAt;
        this.waitedFor = waitedFor;
        this.distanceMeters = distanceMeters;
        this.duration = duration;
        this.placeName = placeName;
        this.latitude = latitude;
        this.longitude = longitude;
        this.bookingId = bookingId;
        this.arrivalOn = arrivalOn;
        this.points.addAll(points);
        this.stay = stay;
    }

    void belongsTo(AdvisorProposal proposal) {
        this.proposal = proposal;
    }
}
