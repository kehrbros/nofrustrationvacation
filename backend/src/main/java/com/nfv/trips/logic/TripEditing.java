package com.nfv.trips.logic;

import jakarta.annotation.Nullable;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Changes of stops and waypoints, followed by planning the affected routes after the commit (routing runs outside of
 * transactions, see {@link RoutePlanning}). Entry point for REST and MCP. The change stays even if planning fails,
 * the outcome is recorded on the route.
 */
@Service
@RequiredArgsConstructor
public class TripEditing {
    private final TripLogic tripLogic;
    private final RoutePlanning routePlanning;

    /** @return outcome per planned route id */
    public Map<Long, PlanResult> addStop(long appUserId, long tripId, NewStop stop, @Nullable Integer position) {
        return plan(tripLogic.addStop(appUserId, tripId, stop, position));
    }

    /** Change by the app itself (chosen departure, stopover), bookings at the stop are not checked. */
    public Map<Long, PlanResult> updateStop(long appUserId, long tripId, long stopId, NewStop stop) {
        return updateStop(appUserId, tripId, stopId, stop, BookingFit.IGNORE);
    }

    /** @see TripLogic#updateStop */
    public Map<Long, PlanResult> updateStop(long appUserId, long tripId, long stopId, NewStop stop, BookingFit fit) {
        return plan(tripLogic.updateStop(appUserId, tripId, stopId, stop, fit));
    }

    public Map<Long, PlanResult> moveStop(long appUserId, long tripId, long stopId, int position) {
        return plan(tripLogic.moveStop(appUserId, tripId, stopId, position));
    }

    public Map<Long, PlanResult> removeStop(long appUserId, long tripId, long stopId) {
        return plan(tripLogic.removeStop(appUserId, tripId, stopId));
    }

    public Map<Long, PlanResult> replaceWaypoints(long appUserId, long tripId, long routeId,
                                                  List<NewWaypoint> waypoints) {
        return plan(tripLogic.replaceWaypoints(appUserId, tripId, routeId, waypoints));
    }

    /**
     * Moves the stop to a new accommodation in another place and plans the routes to and from it, see
     * {@link TripLogic#moveStopToStay}.
     *
     * @return outcome per planned route id, empty within the same place
     */
    public Map<Long, PlanResult> moveStopToStay(long appUserId, long stopId, double latitude, double longitude,
                                                @Nullable String place) {
        return plan(tripLogic.moveStopToStay(appUserId, stopId, latitude, longitude, place));
    }

    /** Plans the route again without a change, e.g. after {@code ROUTING_UNAVAILABLE}. */
    public PlanResult plan(long appUserId, long tripId, long routeId) {
        tripLogic.route(appUserId, tripId, routeId);
        return routePlanning.plan(routeId);
    }

    /** Plans the routes of a change made in another transaction, e.g. a stop extended with its booking. */
    public Map<Long, PlanResult> plan(TripChange change) {
        Map<Long, PlanResult> results = new LinkedHashMap<>();
        for (long routeId : change.routesToPlan()) {
            try {
                results.put(routeId, routePlanning.plan(routeId));
            } catch (EntityNotFoundException e) {
                // deleted by a concurrent change, nothing to plan
            }
        }
        return results;
    }
}