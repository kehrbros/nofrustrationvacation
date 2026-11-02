package com.nfv.recommendations.dto;

import com.nfv.recommendations.entities.OptionKind;
import com.nfv.recommendations.entities.OptionLabel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "One route suggestion, rank 1 is the best")
public record RouteOptionDto(
        long id,
        int rank,
        OptionKind kind,
        List<OptionLabel> labels,
        @Schema(description = "Main road of the part that differs from the fastest way, e.g. SH 2") @Nullable String via,
        OffsetDateTime departureAt,
        @Schema(description = "Arrival at the end stop including waiting and delays") OffsetDateTime arrivalAt,
        double distanceMeters,
        @Schema(description = "Driving time in seconds, without waiting and delays") long durationSeconds,
        long waitingSeconds,
        long delaySeconds,
        @Schema(description = "Part of the distance shared with the planned route (0 to 1), null if not planned")
        @Nullable Double sharedRatio,
        @Schema(description = "Line for the map") List<GeoPointDto> geometry,
        @Schema(description = "Points that become waypoints when applied") List<GeoPointDto> viaPoints,
        @Schema(description = "Incidents still passed on this way") List<ConflictDto> conflicts,
        @Nullable OffsetDateTime appliedAt) {
}