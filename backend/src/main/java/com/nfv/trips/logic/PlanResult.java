package com.nfv.trips.logic;

import com.nfv.trips.entities.PlanOutcome;
import jakarta.annotation.Nullable;

/** Outcome of {@link RoutePlanning#plan}; {@code reason} explains everything but {@code PLANNED}. */
public record PlanResult(PlanOutcome outcome, @Nullable String reason) {
}