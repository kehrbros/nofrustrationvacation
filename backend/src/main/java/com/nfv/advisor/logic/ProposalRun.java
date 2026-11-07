package com.nfv.advisor.logic;

import com.nfv.advisor.entities.AdvisorProposal;
import com.nfv.advisor.entities.AdvisorRun;
import com.nfv.advisor.entities.ProposalGoal;
import com.nfv.advisor.entities.ProposalKind;
import com.nfv.advisor.entities.ProposalStay;
import com.nfv.advisor.entities.RunDecision;
import com.nfv.advisor.entities.RunStatus;

import com.nfv.advisor.entities.RunTrigger;
import com.nfv.incidents.entities.Impact;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A run as the API shows it, read in one transaction.
 *
 * @param stale the situation of the route changed since the run started
 */
public record ProposalRun(long runId, long routeId, @Nullable Long consequenceId, @Nullable Long incidentId,
                          RunTrigger trigger, ProposalGoal goal, RunStatus status,
                          boolean stale, @Nullable String summary, @Nullable String failure, OffsetDateTime createdAt,
                          @Nullable OffsetDateTime finishedAt, @Nullable RunDecision decision,
                          @Nullable Integer chosenRank, @Nullable OffsetDateTime decidedAt, List<Proposal> proposals) {

    public record Proposal(int rank, ProposalKind kind, @Nullable String reason, @Nullable String via,
                           @Nullable OffsetDateTime departureAt, @Nullable OffsetDateTime arrivalAt,
                           @Nullable Double distanceMeters, @Nullable Duration duration, @Nullable Duration delay,
                           @Nullable Double sharedRatio, @Nullable Boolean meetsDeadline, List<Point> points,
                           List<Conflict> conflicts, @Nullable ProposalStay stay) {
    }

    public record Point(double latitude, double longitude, @Nullable String roadHint, @Nullable Long waypointId) {
    }

    public record Conflict(long incidentId, Impact impact, @Nullable Duration delay) {
    }

    static ProposalRun of(AdvisorRun run, boolean stale) {
        return new ProposalRun(run.getId(), run.getRouteId(), run.getConsequenceId(), run.getIncidentId(),
                run.getTrigger(), run.getGoal(), run.getStatus(), stale,
                run.getSummary(), run.getFailure(), run.getCreatedAt(), run.getFinishedAt(), run.getDecision(),
                run.getChosenPosition() == null ? null : run.getChosenPosition() + 1, run.getDecidedAt(),

                run.getProposals().stream().map(ProposalRun::proposal).toList());
    }

    private static Proposal proposal(AdvisorProposal p) {
        return new Proposal(p.getPosition() + 1, p.getKind(), p.getReason(), p.getVia(), p.getDepartureAt(),
                p.getArrivalAt(), p.getDistanceMeters(), p.getDuration(), p.getDelay(), p.getSharedRatio(),
                p.getMeetsDeadline(),
                p.getPoints().stream().map(q -> new Point(q.getLatitude(), q.getLongitude(), q.getRoadHint(),
                        q.getWaypointId())).toList(),
                p.getConflicts().stream().map(c -> new Conflict(c.getIncidentId(), c.getImpact(), c.getDelay()))
                        .toList(),
                p.getStay());
    }
}
