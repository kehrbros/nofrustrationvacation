package com.vacantagent.trips.logic;

import java.util.Set;

/** Result of a change of stops or waypoints: routes that are new or lost their sections, to be planned after commit. */
public record TripChange(long tripId, Set<Long> routesToPlan) {
}