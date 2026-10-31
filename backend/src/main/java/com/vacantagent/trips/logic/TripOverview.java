package com.vacantagent.trips.logic;

import com.vacantagent.trips.entities.Stop;
import com.vacantagent.trips.entities.Trip;
import com.vacantagent.trips.entities.TripPhase;
import jakarta.annotation.Nullable;

/** A trip as shown in the trip list. */
public record TripOverview(Trip trip, TripPhase phase, int stopCount, @Nullable Stop nextStop, boolean hasCoverImage) {
}
