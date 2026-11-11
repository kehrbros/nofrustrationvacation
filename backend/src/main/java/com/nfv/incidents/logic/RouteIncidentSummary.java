package com.nfv.incidents.logic;

import com.nfv.incidents.entities.Category;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Situation of one route, see Docs/consequence-assessment.md#ranking.
 *
 * @param worstCategory   worst category of its current consequences, {@code null} without any
 * @param plannedArrival  arrival without incidents, {@code null} if not planned
 * @param expectedArrival arrival with the delays of the incidents, {@code null} if not passable or not planned
 * @param deadline        arrival of the target stop, {@code null} without ("Ankunftszeit fehlt")
 * @param slack           deadline − expected arrival, negative = too late; {@code null} without either
 * @param unpassable      an incident cannot be passed
 * @param plannedTooLate  even without incidents the arrival is after the deadline
 */
public record RouteIncidentSummary(long routeId, @Nullable Category worstCategory,
                                   @Nullable OffsetDateTime plannedArrival, @Nullable OffsetDateTime expectedArrival,
                                   @Nullable OffsetDateTime deadline, @Nullable Duration slack, boolean unpassable,
                                   boolean plannedTooLate) {
}
