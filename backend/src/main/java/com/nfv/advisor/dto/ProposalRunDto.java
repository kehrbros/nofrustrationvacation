package com.nfv.advisor.dto;

import com.nfv.advisor.entities.ProposalGoal;
import com.nfv.advisor.entities.RunDecision;
import com.nfv.advisor.entities.RunStatus;

import com.nfv.advisor.entities.RunTrigger;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "A run of the agent with its proposals")
public record ProposalRunDto(
        long runId,
        long routeId,
        @Schema(description = "Consequence the run is about, null once it was assessed again") @Nullable Long consequenceId,
        @Schema(description = "Incident the run was started for, null if unknown") @Nullable Long incidentId,
        RunTrigger trigger,
        ProposalGoal goal,
        RunStatus status,
        @Schema(description = "The situation of the route changed since the run started, the numbers may be out of date")
        boolean stale,
        @Schema(description = "Written by the model, null without it") @Nullable String summary,
        @Schema(description = "Why the run failed, e.g. ROUTING_UNAVAILABLE") @Nullable String failure,
        OffsetDateTime createdAt,
        @Nullable OffsetDateTime finishedAt,
        @Schema(description = "null while the user has not decided") @Nullable RunDecision decision,
        @Schema(description = "ACCEPTED: rank of the proposal taken") @Nullable Integer chosenRank,
        @Nullable OffsetDateTime decidedAt,

        @Schema(description = "Best first") List<ProposalDto> proposals) {
}
