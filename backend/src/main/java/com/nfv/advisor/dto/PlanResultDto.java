package com.nfv.advisor.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Schema(description = "What a PLAN leads to, as the backend computed it")
public record PlanResultDto(
        @Schema(description = "Latest check-in of the booked accommodation, else the arrival time of the stop")
        @Nullable OffsetDateTime deadline,
        @Schema(description = "False: the plan moves the target to newTargetName") boolean reachesTarget,
        int extraNights,
        @Schema(type = "string", format = "decimal", example = "128.00", description = "Total of the hotels to pay")
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal extraCost,
        @Nullable String costCurrency,
        @Schema(description = "The arrival is after the planned departure at the target, later stops move")
        boolean affectsFollowing,
        @Nullable String newTargetName) {
}
