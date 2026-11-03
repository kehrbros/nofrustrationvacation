package com.nfv.trips.dto;

import com.nfv.trips.entities.TripPhase;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.List;

public record TripDetailDto(long id, String name, OffsetDateTime startAt, OffsetDateTime endAt, TripPhase phase,
                            @Schema(description = "Number of travelers, suggested as guests of bookings")
                            @Nullable Integer travelers,
                            boolean hasCoverImage, List<StopDto> stops, List<RouteDto> routes) {
}
