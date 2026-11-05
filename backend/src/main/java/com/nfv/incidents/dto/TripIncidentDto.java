package com.nfv.incidents.dto;

import com.nfv.incidents.entities.Category;
import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.entities.MatchKind;
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
        @Schema(description = "Expected delay of the incident in minutes as given, null if unknown")
        @Nullable Long delayMinutes,
        OffsetDateTime validFrom,
        @Schema(description = "null: until further notice") @Nullable OffsetDateTime validTo,
        OffsetDateTime detectedAt,
        OffsetDateTime updatedAt,
        @Schema(description = "Effect on the arrival at the target stop, null if not assessed yet")
        @Nullable Category category,
        @Schema(description = "DIRECT, BY_SHIFT (only hit after earlier delays) or MARGINAL (only within the "
                + "tolerance of the passage, never TOO_LATE)") MatchKind match,
        @Schema(description = "Own delay on this route in minutes (wait at a closure, delay or assumption), "
                + "null: not passable") @Nullable Long assessedDelayMinutes,
        @Schema(description = "assessedDelayMinutes is an assumption of the source, show as estimated")
        boolean delayAssumed,
        @Schema(description = "Passage of the affected section, moved by earlier delays")
        @Nullable OffsetDateTime passageAt,
        @Schema(description = "Arrival at the target stop after this incident, null if not passable or not planned")
        @Nullable OffsetDateTime expectedArrival,
        @Schema(description = "Arrival of the target stop, null without") @Nullable OffsetDateTime deadline,
        @Schema(description = "Time window of the incident the passage falls into, null without windows")
        @Nullable OffsetDateTime windowStart,
        @Nullable OffsetDateTime windowEnd,
        @Schema(description = "Closure whose times are only in the description and could not be read yet: "
                + "show as \"check times\"") boolean timesUnconfirmed) {
}
