package com.nfv.advisor.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import lombok.Getter;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One run of the agent for a route, with the proposals it ended with, see Docs/advisor.md#datenmodell. Derived, not
 * audited. Route and consequence are referenced by id; the database removes the run with the route.
 */
@Getter
@Entity
public class AdvisorRun extends AbstractEntity {
    private static final int MAX_SUMMARY = 2000;

    @Column(nullable = false)
    private long routeId;
    /** The consequence the run is about, {@code null} once it is gone. */
    private @Nullable Long consequenceId;
    @Enumerated(EnumType.STRING)
    @Column(name = "run_trigger", nullable = false, length = 16)
    private RunTrigger trigger;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ProposalGoal goal;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RunStatus status = RunStatus.PENDING;
    /** Prevents a second automatic run for the same situation, {@code null} for runs of the user. */
    private @Nullable String autoKey;
    /** Hash of the situation the proposals were made for, see Docs/advisor.md#veraltete-vorschläge. */
    @Column(nullable = false, length = 64)
    private String situation;
    @Column(length = MAX_SUMMARY)
    private @Nullable String summary;
    @Column(length = 32)
    private @Nullable String failure;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    private @Nullable OffsetDateTime finishedAt;
    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<AdvisorProposal> proposals = new ArrayList<>();

    protected AdvisorRun() {
    }

    public AdvisorRun(long routeId, long consequenceId, RunTrigger trigger, ProposalGoal goal, String situation,
                      @Nullable String autoKey, OffsetDateTime createdAt) {
        this.routeId = routeId;
        this.consequenceId = consequenceId;
        this.trigger = trigger;
        this.goal = goal;
        this.situation = situation;
        this.autoKey = autoKey;
        this.createdAt = createdAt;
    }

    public void started() {
        if (status == RunStatus.PENDING) {
            status = RunStatus.RUNNING;
        }
    }

    public boolean isUnfinished() {
        return status == RunStatus.PENDING || status == RunStatus.RUNNING;
    }

    /** Still unfinished after {@code timeout}: longer than a run can take, so it will not finish any more. */
    public boolean isAbandoned(OffsetDateTime now, Duration timeout) {
        return isUnfinished() && createdAt.plus(timeout).isBefore(now);
    }

    /** Ignored unless unfinished: a late answer does not revive a run that counts as abandoned. */
    public void completed(RunStatus result, @Nullable String summary, List<AdvisorProposal> proposals,
                          OffsetDateTime at) {
        if (!isUnfinished()) {
            return;
        }
        this.status = result;
        this.summary = summary == null || summary.length() <= MAX_SUMMARY ? summary : summary.substring(0, MAX_SUMMARY);
        proposals.forEach(proposal -> {
            proposal.belongsTo(this);
            this.proposals.add(proposal);
        });
        this.finishedAt = at;
    }

    /** A failed automatic run gives up its key, the next assessment may start again. */
    public void failed(String failure, OffsetDateTime at) {
        if (!isUnfinished()) {
            return;
        }
        this.status = RunStatus.FAILED;
        this.failure = failure;
        this.autoKey = null;
        this.finishedAt = at;
    }
}
