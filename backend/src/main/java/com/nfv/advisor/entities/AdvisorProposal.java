package com.nfv.advisor.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import lombok.Getter;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * What the agent proposes. Only {@link #reason} comes from the model, all other values from the backend. Times and
 * distance are empty if they do not apply, e.g. no arrival for {@code KEEP_ROUTE} at a closure that cannot be passed.
 */
@Getter
@Entity
public class AdvisorProposal extends AbstractEntity {
    private static final int MAX_REASON = 1000;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private AdvisorRun run;
    @Column(nullable = false)
    private int position;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ProposalKind kind;
    @Column(length = MAX_REASON)
    private @Nullable String reason;
    private @Nullable String via;
    private @Nullable OffsetDateTime departureAt;
    private @Nullable OffsetDateTime arrivalAt;
    private @Nullable Double distanceMeters;
    private @Nullable Duration duration;
    private @Nullable Duration delay;
    private @Nullable Double sharedRatio;
    private @Nullable Boolean meetsDeadline;
    @ElementCollection
    @CollectionTable(name = "advisor_proposal_point", joinColumns = @JoinColumn(name = "proposal_id"))
    @OrderColumn(name = "position")
    private List<ProposalPoint> points = new ArrayList<>();
    @ElementCollection
    @CollectionTable(name = "advisor_proposal_conflict", joinColumns = @JoinColumn(name = "proposal_id"))
    private List<ProposalConflict> conflicts = new ArrayList<>();

    protected AdvisorProposal() {
    }

    public AdvisorProposal(int position, ProposalKind kind, @Nullable String reason, @Nullable String via,
                           @Nullable OffsetDateTime departureAt, @Nullable OffsetDateTime arrivalAt,
                           @Nullable Double distanceMeters, @Nullable Duration duration, @Nullable Duration delay,
                           @Nullable Double sharedRatio, @Nullable Boolean meetsDeadline, List<ProposalPoint> points,
                           List<ProposalConflict> conflicts) {
        this.position = position;
        this.kind = kind;
        this.reason = reason == null || reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON);
        this.via = via;
        this.departureAt = departureAt;
        this.arrivalAt = arrivalAt;
        this.distanceMeters = distanceMeters;
        this.duration = duration;
        this.delay = delay;
        this.sharedRatio = sharedRatio;
        this.meetsDeadline = meetsDeadline;
        this.points.addAll(points);
        this.conflicts.addAll(conflicts);
    }

    void belongsTo(AdvisorRun run) {
        this.run = run;
    }
}
