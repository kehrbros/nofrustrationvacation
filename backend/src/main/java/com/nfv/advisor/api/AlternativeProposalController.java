package com.nfv.advisor.api;

import com.nfv.advisor.dto.AcceptedProposalDto;
import com.nfv.advisor.dto.ProposalDto;
import com.nfv.advisor.dto.ProposalStayDto;
import com.nfv.advisor.dto.ProposalRequestDto;
import com.nfv.advisor.dto.ProposalRunDto;
import com.nfv.advisor.dto.ProposalRunStartedDto;
import com.nfv.advisor.dto.RouteProposalRunDto;
import com.nfv.advisor.dto.TripProposalRunsDto;
import com.nfv.advisor.entities.ProposalStay;
import com.nfv.advisor.logic.AlternativeProposals;
import com.nfv.advisor.logic.ProposalDecisions;

import com.nfv.advisor.logic.ProposalRun;
import com.nfv.recommendations.dto.ChoicePointDto;
import com.nfv.recommendations.dto.ConflictDto;
import com.nfv.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@Tag(name = "Alternative proposals", description = "What the agent proposes when incidents affect a route, see "
        + "Docs/advisor.md. Choosing goes through PUT /routes/{routeId}/choice.")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AlternativeProposalController {
    private final AlternativeProposals proposals;
    private final ProposalDecisions decisions;

    @Operation(summary = "Start a run of the agent", description = "Runs in the background and can take minutes, poll "
            + "the run. If one is already running for the route, its id comes back. 409 NO_ACTIVE_CONSEQUENCE.")
    @PostMapping("/routes/{routeId}/alternative-proposals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProposalRunStartedDto start(@AuthenticationPrincipal CurrentUser user, @PathVariable long routeId,
                                       // no @Nullable here: springdoc would make the schema of the body type null
                                       @RequestBody(required = false) ProposalRequestDto request) {
        ProposalRequestDto values = request == null ? new ProposalRequestDto(null, null, null) : request;
        return new ProposalRunStartedDto(proposals.start(user.appUserId(), routeId, values.consequenceId(),
                values.incidentId(), values.goal()));
    }

    @Operation(summary = "Last run of every route of a trip", description = "Routes without a run are left out.")
    @GetMapping("/trips/{tripId}/alternative-proposals")
    public TripProposalRunsDto latestOfTrip(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId) {
        return new TripProposalRunsDto(proposals.latestOfTrip(user.appUserId(), tripId).stream()
                .map(AlternativeProposalController::toDto).toList());
    }

    @Operation(summary = "Take a proposal", description = "Changes the trip: REROUTE and SHIFT_DEPARTURE like PUT "
            + "/routes/{routeId}/choice, STOPOVER adds a stop at the hotel before the target stop (the route is split), "
            + "KEEP_ROUTE and TARGET_STAY change nothing. Hotels are booked afterwards, see the answer. Replaces an "
            + "earlier decision. 409 PROPOSAL_NOT_APPLICABLE (run not finished, trip changed), 400 "
            + "INVALID_PROPOSAL_INPUT (no such rank).")
    @PostMapping("/alternative-proposals/{runId}/proposals/{rank}/acceptance")
    public AcceptedProposalDto accept(@AuthenticationPrincipal CurrentUser user, @PathVariable long runId,
                                      @PathVariable int rank) {
        ProposalDecisions.Accepted accepted = decisions.accept(user.appUserId(), runId, rank);
        return new AcceptedProposalDto(accepted.runId(), accepted.rank(), accepted.kind(), accepted.tripId(),
                accepted.plannedRouteIds(), accepted.stayStopId(), accepted.replacesBookingId(), accepted.todosAdded());
    }

    @Operation(summary = "Dismiss all proposals", description = "The user plans again by hand. New proposals can be "
            + "asked for at any time.")
    @PostMapping("/alternative-proposals/{runId}/dismissal")
    public ProposalRunDto dismiss(@AuthenticationPrincipal CurrentUser user, @PathVariable long runId) {
        decisions.dismiss(user.appUserId(), runId);
        return toDto(proposals.get(user.appUserId(), runId));
    }

    @Operation(summary = "Last run of a route", description = "Also a run started automatically; run is null without.")
    @GetMapping("/routes/{routeId}/alternative-proposals")
    public RouteProposalRunDto latest(@AuthenticationPrincipal CurrentUser user, @PathVariable long routeId) {
        return new RouteProposalRunDto(proposals.latest(user.appUserId(), routeId)
                .map(AlternativeProposalController::toDto).orElse(null));
    }

    @Operation(summary = "A run with its proposals")
    @GetMapping("/alternative-proposals/{runId}")
    public ProposalRunDto get(@AuthenticationPrincipal CurrentUser user, @PathVariable long runId) {
        return toDto(proposals.get(user.appUserId(), runId));
    }

    private static ProposalRunDto toDto(ProposalRun run) {
        return new ProposalRunDto(run.runId(), run.routeId(), run.consequenceId(), run.incidentId(), run.trigger(),
                run.goal(), run.status(), run.stale(), run.summary(), run.failure(), run.createdAt(), run.finishedAt(),
                run.decision(), run.chosenRank(), run.decidedAt(),

                run.proposals().stream().map(AlternativeProposalController::toDto).toList());
    }

    private static ProposalDto toDto(ProposalRun.Proposal p) {
        return new ProposalDto(p.rank(), p.kind(), p.reason(), p.via(), p.departureAt(), p.arrivalAt(),
                p.distanceMeters(), seconds(p.duration()), seconds(p.delay()), p.sharedRatio(), p.meetsDeadline(),
                p.points().stream().map(q -> new ChoicePointDto(q.latitude(), q.longitude(), q.roadHint(),
                        q.waypointId())).toList(),
                p.conflicts().stream().map(c -> new ConflictDto(c.incidentId(), c.impact(), seconds(c.delay())))
                        .toList(),
                p.stay() == null ? null : toDto(p.stay()));
    }

    private static ProposalStayDto toDto(ProposalStay s) {
        return new ProposalStayDto(s.hotelId(), s.offerId(), s.hotelName(), s.latitude(), s.longitude(), s.place(),
                s.distanceMeters(), s.checkIn(), s.checkOut(), s.guests(), s.rooms(), s.price(), s.currency(),
                s.refundable(), s.freeCancellationUntil(), s.replacesBookingId());
    }

    private static @Nullable Long seconds(@Nullable Duration duration) {
        return duration == null ? null : duration.toSeconds();
    }
}
