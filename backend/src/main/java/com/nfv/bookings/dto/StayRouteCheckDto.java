package com.nfv.bookings.dto;

import com.nfv.bookings.logic.StayRouteCheck;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

@Schema(description = "Way to the hotel of a reservation, checked before paying. Only if the hotel lies in another "
        + "place than the stop (otherPlace): the stop moves there after booking. Computed, not stored; a hint only.")
public record StayRouteCheckDto(
        @Schema(description = "Hotel more than 10 km from the stop, the stop moves there after booking") boolean otherPlace,
        int distanceMeters,
        @Schema(description = "Route to the stop") @Nullable Long routeId,
        @Schema(description = "Arrival on the best way, including delays of incidents") @Nullable OffsetDateTime arrivalAt,
        @Schema(description = "Latest check-in at the hotel, if known") @Nullable OffsetDateTime checkInUntil,
        @Schema(description = "Arrival after the latest check-in") boolean late,
        @Schema(description = "Even the best way passes a closure") boolean blocked,
        @Schema(description = "Delay by incidents in seconds") long delaySeconds,
        @Schema(description = "Why the way was not checked") @Nullable StayRouteCheck.Problem problem) {
}
