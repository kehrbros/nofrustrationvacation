package com.nfv.advisor.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The run that was started, or the one already running for the route")
public record ProposalRunStartedDto(long runId) {
}
