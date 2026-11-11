package com.nfv.advisor.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * A plan the user takes, see Docs/incident-plans.md#one-click-execution. Keeps a copy of the plan: a night on
 * the way splits the route, the run and its proposals go with it. Route and run are by id without a foreign key for
 * the same reason.
 */
@Getter
@Entity
public class AdvisorPlanExecution extends AbstractEntity {
    private static final int MAX_FAILURE = 500;

    @Column(nullable = false)
    private long appUserId;
    @Column(nullable = false)
    private long tripId;
    @Column(nullable = false)
    private long routeId;
    @Column(nullable = false)
    private long runId;
    @Column(nullable = false)
    private int proposalRank;
    /** JSON, see {@code PlanSnapshot}. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String plan;
    private @Nullable Long planPaymentId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PlanExecutionStatus status;
    @Column(length = MAX_FAILURE)
    private @Nullable String failure;
    /** JSON of what the plan changed. */
    @Column(columnDefinition = "TEXT")
    private @Nullable String outcome;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    private @Nullable OffsetDateTime appliedAt;

    protected AdvisorPlanExecution() {
    }

    public AdvisorPlanExecution(long appUserId, long tripId, long routeId, long runId, int proposalRank, String plan,
                                boolean paid, OffsetDateTime createdAt) {
        this.appUserId = appUserId;
        this.tripId = tripId;
        this.routeId = routeId;
        this.runId = runId;
        this.proposalRank = proposalRank;
        this.plan = plan;
        this.status = paid ? PlanExecutionStatus.AWAITING_PAYMENT : PlanExecutionStatus.APPLYING;
        this.createdAt = createdAt;
    }

    /** The hotels are prebooked under this payment. */
    public void paidWith(long planPaymentId) {
        require(PlanExecutionStatus.AWAITING_PAYMENT);
        this.planPaymentId = planPaymentId;
    }

    /** @return false if someone else applies it already or it is done */
    public boolean claim() {
        if (status != PlanExecutionStatus.AWAITING_PAYMENT) {
            return false;
        }
        status = PlanExecutionStatus.APPLYING;
        return true;
    }

    public void applied(String outcome, OffsetDateTime at) {
        require(PlanExecutionStatus.APPLYING);
        this.status = PlanExecutionStatus.APPLIED;
        this.outcome = outcome;
        this.appliedAt = at;
    }

    public void appliedPartly(String failure, OffsetDateTime at) {
        require(PlanExecutionStatus.APPLYING);
        this.status = PlanExecutionStatus.APPLIED_PARTLY;
        this.failure = cut(failure);
        this.appliedAt = at;
    }

    /** The payment failed or was cancelled: nothing changed. */
    public void failed(String failure) {
        if (status == PlanExecutionStatus.AWAITING_PAYMENT) {
            this.status = PlanExecutionStatus.FAILED;
            this.failure = cut(failure);
        }
    }

    private void require(PlanExecutionStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("plan execution " + getId() + " is " + status + ", not " + expected);
        }
    }

    private static String cut(String text) {
        return text.length() <= MAX_FAILURE ? text : text.substring(0, MAX_FAILURE);
    }
}
