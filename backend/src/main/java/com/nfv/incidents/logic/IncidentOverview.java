package com.nfv.incidents.logic;

import com.nfv.incidents.entities.Category;
import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.entities.MatchKind;
import com.nfv.incidents.entities.VehicleScope;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * An incident with the roads it was located on and its consequences on all routes, for diagnosis (test endpoints, see
 * Docs/traffic-feed.md#testquelle-manual), not for travellers.
 *
 * @param segments number of road segments
 */
public record IncidentOverview(long id, IncidentType type, Impact impact, @Nullable String description,
                               OffsetDateTime validFrom, @Nullable OffsetDateTime validTo,
                               @Nullable Duration expectedDelay, @Nullable Duration assumedDelay,
                               boolean timesUnconfirmed, VehicleScope vehicleScope, List<TimeWindow> windows,
                               int segments, List<Road> roads, List<Effect> consequences) {
    /** Part of a road the incident is located on, per road number and name. */
    public record Road(@Nullable String ref, @Nullable String name, int segments, double lengthMeters) {
    }

    /** Consequence on one route. */
    public record Effect(long routeId, @Nullable Category category, MatchKind match, Impact impact,
                         @Nullable Duration assessedDelay, boolean delayAssumed, @Nullable OffsetDateTime passageAt,
                         @Nullable OffsetDateTime expectedArrival, @Nullable OffsetDateTime deadline,
                         @Nullable OffsetDateTime windowStart, @Nullable OffsetDateTime windowEnd) {
    }
}
