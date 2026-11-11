package com.nfv.trips.logic;

import com.nfv.trips.data.RouteRepository;
import com.nfv.trips.data.RouteSectionRepository;
import com.nfv.trips.data.WaypointRepository;
import com.nfv.trips.entities.Route;
import com.nfv.trips.entities.Stop;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads routes for other modules as {@link RouteSnapshot}, see Docs/recommendations.md#interfaces. */
@Service
@RequiredArgsConstructor
public class RouteSnapshots {
    private final RouteRepository routeRepository;
    private final WaypointRepository waypointRepository;
    private final RouteSectionRepository routeSectionRepository;

    /**
     * The route of a trip of the user.
     *
     * @throws TripItemNotFoundException the route does not exist or belongs to another user, both look the same
     */
    @Transactional(readOnly = true)
    public RouteSnapshot of(long appUserId, long routeId) {
        Route route = routeRepository.findById(routeId)
                .filter(r -> r.getTrip().getAppUserId() == appUserId)
                .orElseThrow(() -> new TripItemNotFoundException("route", routeId));
        return snapshot(route);
    }

    /**
     * The user the route belongs to, for work in the background where nobody is logged in.
     *
     * @throws TripItemNotFoundException the route does not exist (any more)
     */
    @Transactional(readOnly = true)
    public long ownerOf(long routeId) {
        return routeRepository.findById(routeId).map(route -> route.getTrip().getAppUserId())
                .orElseThrow(() -> new TripItemNotFoundException("route", routeId));
    }

    private RouteSnapshot snapshot(Route route) {
        return new RouteSnapshot(route.getId(), route.getTrip().getId(), stop(route.getFromStop()),
                stop(route.getToStop()),
                waypointRepository.findByRouteIdOrderByPosition(route.getId()).stream()
                        .map(w -> new RouteSnapshot.WaypointPoint(w.getId(), w.getLatitude(), w.getLongitude(),
                                w.getName(), w.getRoadHint(), w.getOrigin()))
                        .toList(),
                routeSectionRepository.findByRouteIdOrderByPosition(route.getId()).stream()
                        .map(s -> new PlannedSection(s.getSegmentId(), s.getFromFraction(), s.getToFraction(),
                                s.getExpectedPassageStart(), s.getExpectedPassageEnd()))
                        .toList());
    }

    private static RouteSnapshot.StopPoint stop(Stop stop) {
        return new RouteSnapshot.StopPoint(stop.getId(), stop.getName(), stop.getLatitude(), stop.getLongitude(),
                stop.getArrivalAt(), stop.getDepartureAt());
    }
}