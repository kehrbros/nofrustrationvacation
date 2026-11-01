package com.vacantagent.trips.logic;

import com.vacantagent.trips.entities.PlanOutcome;
import jakarta.annotation.Nullable;

/** Outcome of {@link RoutePlanning#plan}; {@code reason} explains everything but {@code PLANNED}. */
public record PlanResult(PlanOutcome outcome, @Nullable String reason) {
}