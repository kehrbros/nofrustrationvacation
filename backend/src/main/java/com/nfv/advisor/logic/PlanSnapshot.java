package com.nfv.advisor.logic;

import com.nfv.advisor.entities.AdvisorProposal;
import com.nfv.advisor.entities.AdvisorProposalStep;
import com.nfv.advisor.entities.PlanStepKind;
import com.nfv.advisor.entities.ProposalKind;
import com.nfv.advisor.entities.ProposalStay;
import com.nfv.recommendations.logic.SuggestedOption;
import jakarta.annotation.Nullable;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * The steps of a plan as taking it needs them, stored as JSON with the execution: a night on the way splits the route
 * and removes the run with the proposal.
 */
record PlanSnapshot(List<Step> steps) {
    /** Keeps the offsets: they are the local times at the stops. */
    static final JsonMapper JSON = JsonMapper.builder()
            .disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).build();

    record Step(PlanStepKind kind, @Nullable ProposalKind routeKind, @Nullable OffsetDateTime departureAt,
                @Nullable OffsetDateTime arrivalAt, @Nullable OffsetDateTime waitedFor, @Nullable String placeName,
                @Nullable Long bookingId, @Nullable LocalDate arrivalOn, List<SuggestedOption.Point> points,
                @Nullable ProposalStay stay) {
    }

    /** Within the transaction that read the proposal. */
    static PlanSnapshot of(AdvisorProposal proposal) {
        return new PlanSnapshot(proposal.getSteps().stream().map(PlanSnapshot::step).toList());
    }

    private static Step step(AdvisorProposalStep s) {
        return new Step(s.getKind(), s.getRouteKind(), s.getDepartureAt(), s.getArrivalAt(), s.getWaitedFor(),
                s.getPlaceName(), s.getBookingId(), s.getArrivalOn(),
                s.getPoints().stream().map(p -> new SuggestedOption.Point(p.getLatitude(), p.getLongitude(),
                        p.getRoadHint(), p.getWaypointId())).toList(),
                s.getStay());
    }

    /** Hotels to pay, with the key the payment knows them by. */
    List<Step> stays() {
        return steps.stream().filter(step -> step.stay() != null).toList();
    }

    static String key(Step step) {
        return step.kind() == PlanStepKind.OVERNIGHT ? "overnight" : "target";
    }

    String json() {
        return JSON.writeValueAsString(this);
    }

    static PlanSnapshot parse(String json) {
        return JSON.readValue(json, PlanSnapshot.class);
    }
}
