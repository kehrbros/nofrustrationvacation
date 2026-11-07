package com.nfv.advisor.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "The last run of a route")
public record RouteProposalRunDto(
        // no @Nullable: springdoc would turn the referenced schema itself into type null
        @Schema(description = "Absent if the route has no run") ProposalRunDto run) {

}
