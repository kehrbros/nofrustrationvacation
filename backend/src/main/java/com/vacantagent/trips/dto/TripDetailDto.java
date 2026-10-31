package com.vacantagent.trips.dto;

import com.vacantagent.trips.entities.TripPhase;

import java.time.OffsetDateTime;
import java.util.List;

public record TripDetailDto(long id, String name, OffsetDateTime startAt, OffsetDateTime endAt, TripPhase phase,
                            boolean hasCoverImage, List<StopDto> stops, List<RouteDto> routes) {
}
