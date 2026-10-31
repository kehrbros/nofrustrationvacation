package com.vacantagent.trips.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

@Schema(description = "Way between two consecutive stops")
public record RouteDto(long id, long fromStopId, long toStopId,
                       @Schema(description = "When the way was last planned, null until a routing service planned it")
                       @Nullable OffsetDateTime plannedAt) {
}
