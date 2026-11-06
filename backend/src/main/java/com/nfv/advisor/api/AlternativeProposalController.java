package com.nfv.advisor.api;

import com.nfv.advisor.dto.ProposalDto;
import com.nfv.advisor.dto.ProposalRequestDto;
import com.nfv.advisor.dto.ProposalRunDto;
import com.nfv.advisor.dto.ProposalRunStartedDto;
import com.nfv.advisor.dto.RouteProposalRunDto;
import com.nfv.advisor.logic.AlternativeProposals;
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

    @Operation(summary = "Start a run of the agent", description = "Runs in the background and can take minutes, poll "
            + "the run. If one is already running for the route, its id comes back. 409 NO_ACTIVE_CONSEQUENCE.")
    @PostMapping("/routes/{routeId}/alternative-proposals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProposalRunStartedDto start(@AuthenticationPrincipal CurrentUser user, @PathVariable long routeId,
                                       // no @Nullable here: springdoc would make the schema of the body type null
                                       @RequestBody(required = false) ProposalRequestDto request) {
        ProposalRequestDto values = request == null ? new ProposalRequestDto(null, null) : request;
        return new ProposalRunStartedDto(proposals.start(user.appUserId(), routeId, values.consequenceId(),
                values.goal()));
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
        return new ProposalRunDto(run.runId(), run.routeId(), run.trigger(), run.goal(), run.status(), run.stale(),
                run.summary(), run.failure(), run.createdAt(), run.finishedAt(),
                run.proposals().stream().map(AlternativeProposalController::toDto).toList());
    }

    private static ProposalDto toDto(ProposalRun.Proposal p) {
        return new ProposalDto(p.rank(), p.kind(), p.reason(), p.via(), p.departureAt(), p.arrivalAt(),
                p.distanceMeters(), seconds(p.duration()), seconds(p.delay()), p.sharedRatio(), p.meetsDeadline(),
                p.points().stream().map(q -> new ChoicePointDto(q.latitude(), q.longitude(), q.roadHint(),
                        q.waypointId())).toList(),
                p.conflicts().stream().map(c -> new ConflictDto(c.incidentId(), c.impact(), seconds(c.delay())))
                        .toList());
    }

    private static @Nullable Long seconds(@Nullable Duration duration) {
        return duration == null ? null : duration.toSeconds();
    }
}
