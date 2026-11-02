package com.nfv.incidents.dto;

import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.entities.IncidentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

@Schema(description = "Incident affecting a route of a trip, one entry per incident and route")
public record TripIncidentDto(
        long incidentId,
        long routeId,
        IncidentType type,
        @Nullable String description,
        Impact impact,
        @Schema(description = "Expected delay on this route in minutes, null if unknown") @Nullable Long delayMinutes,
        OffsetDateTime validFrom,
        @Schema(description = "null: until further notice") @Nullable OffsetDateTime validTo,
        OffsetDateTime detectedAt,
        OffsetDateTime updatedAt) {
}
