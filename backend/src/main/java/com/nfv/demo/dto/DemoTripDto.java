package com.nfv.demo.dto;

import com.nfv.demo.logic.DemoTrips;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.util.List;

@Schema(description = "The demo trip just created, with what became of its test incidents")
public record DemoTripDto(long tripId, List<Incident> incidents) {

    @Schema(description = "A test incident of the demo trip")
    public record Incident(String key,
                           @Schema(description = "Placed onto the planned route of the trip") boolean placedOnRoute,
                           @Schema(description = "INCIDENT, PENDING or why there is no incident") @Nullable String outcome) {
    }

    public static DemoTripDto of(DemoTrips.DemoTrip trip) {
        return new DemoTripDto(trip.tripId(), trip.incidents().stream()
                .map(i -> new Incident(i.key(), i.placedOnRoute(), i.outcome())).toList());
    }
}
