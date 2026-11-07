package com.nfv.advisor.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "The last run of every route of a trip that has one")
public record TripProposalRunsDto(List<ProposalRunDto> runs) {
}
