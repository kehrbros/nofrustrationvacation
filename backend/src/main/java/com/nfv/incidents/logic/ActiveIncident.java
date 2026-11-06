package com.nfv.incidents.logic;

import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.entities.VehicleScope;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Incident for other modules, without the entity, see {@link ActiveIncidents}.
 *
 * @param assumedDelay delay the source assumes without giving a figure
 * @param windows      empty: applies during the whole validity
 */
public record ActiveIncident(long id, Impact impact, @Nullable Duration expectedDelay, @Nullable Duration assumedDelay,
                             OffsetDateTime validFrom, @Nullable OffsetDateTime validTo, Set<Long> segmentIds,
                             List<TimeWindow> windows, VehicleScope vehicleScope) {
    public ActiveIncident {
        windows = List.copyOf(windows);
    }

    /** Delay when passing as in the assessment: the given one, else the assumption of the source. */
    public @Nullable Duration delay() {
        return expectedDelay != null ? expectedDelay : assumedDelay;
    }

    /** {@code HEAVY_ONLY} does not affect cars, as in the assessment. */
    public boolean affectsCars() {
        return vehicleScope != VehicleScope.HEAVY_ONLY;
    }

    /** Whether a passage during {@code [start, end]} falls into the validity and, if any, into a window. */
    public boolean overlaps(OffsetDateTime start, OffsetDateTime end) {
        return IncidentOverlap.overlaps(validFrom, validTo, windows, start, end);
    }
}
