package com.nfv.advisor.dto;

import com.nfv.advisor.entities.PlanStepKind;
import com.nfv.advisor.entities.ProposalKind;
import com.nfv.recommendations.dto.ChoicePointDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "A step of a PLAN in order. DRIVE: the way (to the night, to the target or to another target). "
        + "OVERNIGHT: a night at the hotel in stay, a stop there before the target. CONTINUE: the way on the next "
        + "morning, checked. KEEP_BOOKING: the booked accommodation stays, it is told about the arrival. REPLACE_STAY: "
        + "another accommodation at the target. NEW_TARGET: the target stop moves to the hotel in stay.")
public record PlanStepDto(
        PlanStepKind kind,
        @Schema(description = "DRIVE and CONTINUE: REROUTE, SHIFT_DEPARTURE or KEEP_ROUTE") @Nullable ProposalKind routeKind,
        @Nullable String via,
        @Nullable OffsetDateTime departureAt,
        @Nullable OffsetDateTime arrivalAt,
        @Schema(description = "CONTINUE: leaves when a closure ends, later than usual") @Nullable OffsetDateTime waitedFor,
        @Nullable Double distanceMeters,
        @Nullable Long durationSeconds,
        @Schema(description = "Area or other target of the step") @Nullable String placeName,
        @Nullable Double latitude,
        @Nullable Double longitude,
        @Schema(description = "KEEP_BOOKING: the booking that stays") @Nullable Long bookingId,
        @Schema(description = "KEEP_BOOKING: tell the accommodation the guests arrive on this day, null if only late")
        @Nullable LocalDate arrivalOn,
        List<ChoicePointDto> waypoints,
        // no @Nullable: springdoc would turn the referenced schema itself into type null
        @Schema(description = "OVERNIGHT, REPLACE_STAY and NEW_TARGET only, absent otherwise") ProposalStayDto stay) {
}
