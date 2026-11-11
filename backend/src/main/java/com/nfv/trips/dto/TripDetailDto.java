package com.nfv.trips.dto;

import com.nfv.trips.entities.TripPhase;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.List;

public record TripDetailDto(long id, String name, OffsetDateTime startAt, OffsetDateTime endAt, TripPhase phase,
                            @Schema(description = "Number of travelers, suggested as guests of bookings")
                            @Nullable Integer travelers,
                            // no @Nullable: springdoc would turn the referenced schema itself into type null
                            @Schema(description = "Guest remembered for the bookings of this trip, absent if none")
                            TripGuestDto guest,
                            boolean hasCoverImage, List<StopDto> stops, List<RouteDto> routes) {
}
