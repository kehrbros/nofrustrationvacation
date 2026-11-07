package com.nfv.advisor.dto;

import com.nfv.advisor.entities.ProposalKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.util.List;

@Schema(description = "What taking a proposal changed. STOPOVER and TARGET_STAY: book the hotel next, with "
        + "replacesBookingId via POST /accommodation-bookings/{replacesBookingId}/alternative-reservations, otherwise "
        + "via POST /stops/{stayStopId}/stay-reservations.")
public record AcceptedProposalDto(
        long runId,
        int rank,
        ProposalKind kind,
        long tripId,
        @Schema(description = "Routes planned again; a stopover splits the route, the old one is gone")
        List<Long> plannedRouteIds,
        @Schema(description = "STOPOVER: the new stop at the hotel. TARGET_STAY: the target stop") @Nullable Long stayStopId,
        @Schema(description = "TARGET_STAY: the booking the hotel replaces") @Nullable Long replacesBookingId,
        @Schema(description = "New entries on the to-do list of the trip") int todosAdded) {
}
