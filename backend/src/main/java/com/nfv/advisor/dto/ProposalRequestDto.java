package com.nfv.advisor.dto;

import com.nfv.advisor.entities.ProposalGoal;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

@Schema(description = "Request for a run of the agent")
public record ProposalRequestDto(
        @Schema(description = "Consequence to look at, default: the worst on the route") @Nullable Long consequenceId,
        @Schema(description = "Default ARRIVE_ON_TIME") @Nullable ProposalGoal goal) {
}
