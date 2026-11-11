package com.nfv.trips.logic;

import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;

/**
 * Times of a route for the assessment of its consequences, see Docs/consequence-assessment.md#reference-time.
 *
 * @param departure      start of the first planned section, {@code null} if not planned
 * @param plannedArrival end of the last planned section, {@code null} if not planned
 * @param deadline       arrival at the target stop ({@code Stop.arrivalAt}), {@code null} without
 */
public record RouteSchedule(long routeId, long tripId, @Nullable OffsetDateTime departure,
                            @Nullable OffsetDateTime plannedArrival, @Nullable OffsetDateTime deadline) {
}
