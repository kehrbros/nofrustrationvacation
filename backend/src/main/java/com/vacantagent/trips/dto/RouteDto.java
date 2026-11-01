package com.vacantagent.trips.dto;

import com.vacantagent.trips.entities.PlanOutcome;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

@Schema(description = "Way between two consecutive stops")
public record RouteDto(long id, long fromStopId, long toStopId,
                       @Schema(description = "When the way was last planned, null until planned or after a change of "
                               + "its stops or waypoints")
                       @Nullable OffsetDateTime plannedAt,
                       @Schema(description = "Outcome of the last planning, null until planned or after a change")
                       @Nullable PlanOutcome planOutcome,
                       @Schema(description = "Why the last planning failed")
                       @Nullable String planReason) {
}