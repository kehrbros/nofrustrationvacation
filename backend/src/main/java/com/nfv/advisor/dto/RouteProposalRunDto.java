package com.nfv.advisor.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

@Schema(description = "The last run of a route")
public record RouteProposalRunDto(@Schema(description = "null if the route has no run") @Nullable ProposalRunDto run) {
}
