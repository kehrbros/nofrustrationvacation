package com.nfv.advisor.dto;

import com.nfv.advisor.entities.ProposalKind;
import com.nfv.recommendations.dto.ChoicePointDto;
import com.nfv.recommendations.dto.ConflictDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "What the agent proposes. REROUTE: send waypoints to PUT /routes/{routeId}/choice. "
        + "SHIFT_DEPARTURE: send departureAt there. KEEP_ROUTE: nothing to choose.")
public record ProposalDto(
        @Schema(description = "1 is the best") int rank,
        ProposalKind kind,
        @Schema(description = "Written by the model, null without it") @Nullable String reason,
        @Schema(description = "Main road of the part that differs, e.g. SH 2") @Nullable String via,
        @Nullable OffsetDateTime departureAt,
        @Schema(description = "Including waiting and delays, null if a closure cannot be passed")
        @Nullable OffsetDateTime arrivalAt,
        @Nullable Double distanceMeters,
        @Nullable Long durationSeconds,
        @Nullable Long delaySeconds,
        @Schema(description = "Part of the distance shared with the planned route") @Nullable Double sharedRatio,
        @Schema(description = "Reaches the arrival time of the target stop, null if it has none")
        @Nullable Boolean meetsDeadline,
        List<ChoicePointDto> waypoints,
        @Schema(description = "Incidents still passed on this way") List<ConflictDto> conflicts) {
}
