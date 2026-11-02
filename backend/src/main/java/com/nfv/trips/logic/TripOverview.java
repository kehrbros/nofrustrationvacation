package com.nfv.trips.logic;

import com.nfv.trips.entities.Stop;
import com.nfv.trips.entities.Trip;
import com.nfv.trips.entities.TripPhase;
import jakarta.annotation.Nullable;

/** A trip as shown in the trip list. */
public record TripOverview(Trip trip, TripPhase phase, int stopCount, @Nullable Stop nextStop, boolean hasCoverImage) {
}
