package com.nfv.incidents.dto;

import com.nfv.incidents.entities.Category;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

@Schema(description = "Situation of a route with its incidents, against the arrival at its target stop")
public record RouteIncidentSummaryDto(
        long routeId,
        @Schema(description = "Worst category of its current incidents, null without") @Nullable Category worstCategory,
        @Schema(description = "Arrival without incidents, null if not planned") @Nullable OffsetDateTime plannedArrival,
        @Schema(description = "Arrival with the incidents, null if not passable or not planned")
        @Nullable OffsetDateTime expectedArrival,
        @Schema(description = "Arrival of the target stop, null: arrival time missing") @Nullable OffsetDateTime deadline,
        @Schema(description = "Deadline minus expected arrival in minutes, negative: too late; null without either")
        @Nullable Long slackMinutes,
        @Schema(description = "An incident on the route cannot be passed") boolean unpassable,
        @Schema(description = "Even without incidents the arrival is after the deadline") boolean plannedTooLate) {
}
