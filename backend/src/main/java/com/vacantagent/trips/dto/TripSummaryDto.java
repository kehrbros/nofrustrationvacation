package com.vacantagent.trips.dto;

import com.vacantagent.trips.entities.TripPhase;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

@Schema(description = "Trip in the trip list")
public record TripSummaryDto(
        long id,
        String name,
        @Schema(description = "Begin of the trip with the offset it was entered in") OffsetDateTime startAt,
        @Schema(description = "End of the trip (inclusive) with the offset it was entered in") OffsetDateTime endAt,
        @Schema(description = "Relative to the time of the request") TripPhase phase,
        int stopCount,
        @Schema(description = "Next stop with an arrival in the future") @Nullable String nextStopName,
        boolean hasCoverImage) {
}
