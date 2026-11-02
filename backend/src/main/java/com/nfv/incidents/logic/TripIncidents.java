package com.nfv.incidents.logic;

import com.nfv.incidents.data.ConsequenceRepository;
import com.nfv.incidents.entities.Consequence;
import com.nfv.trips.entities.Route;
import com.nfv.trips.logic.TripLogic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Incidents as the travellers see them: per trip, via the consequences on its routes. Checks trip ownership. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TripIncidents {
    private final TripLogic tripLogic;
    private final ConsequenceRepository consequenceRepository;
    private final Clock clock;

    /** Consequences on the routes of the trip whose incident has not ended yet, with the incident loaded. */
    public List<Consequence> active(long appUserId, long tripId) {
        List<Long> routeIds = tripLogic.routes(appUserId, tripId).stream().map(Route::getId).toList();
        return routeIds.isEmpty() ? List.of()
                : consequenceRepository.findActiveOnRoutes(routeIds, OffsetDateTime.now(clock));
    }

    /** Trip id → number of active consequences, only trips with at least one. */
    public Map<Long, Integer> activeCountsByTrip(long appUserId) {
        Map<Long, Long> tripIdsByRouteId = tripLogic.tripIdsByRouteId(appUserId);
        Map<Long, Integer> counts = new TreeMap<>();
        if (!tripIdsByRouteId.isEmpty()) {
            consequenceRepository.findActiveOnRoutes(tripIdsByRouteId.keySet(), OffsetDateTime.now(clock)).forEach(
                    consequence -> counts.merge(tripIdsByRouteId.get(consequence.getRouteId()), 1, Integer::sum));
        }
        return counts;
    }
}
