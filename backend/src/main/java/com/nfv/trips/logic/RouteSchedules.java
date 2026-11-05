package com.nfv.trips.logic;

import com.nfv.trips.data.RouteRepository;
import com.nfv.trips.data.RouteSectionRepository;
import com.nfv.trips.entities.Route;
import com.nfv.trips.entities.RouteSection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Reads {@link RouteSchedule}s for the assessment in the incidents module. */
@Service
@RequiredArgsConstructor
public class RouteSchedules {
    private final RouteRepository routeRepository;
    private final RouteSectionRepository routeSectionRepository;
    private final TripLogic tripLogic;

    /** Schedule of the route, empty if it does not exist (any more). No ownership check, for internal use. */
    @Transactional(readOnly = true)
    public Optional<RouteSchedule> of(long routeId) {
        return routeRepository.findById(routeId).map(this::schedule);
    }

    /** Schedules of all routes of the trip of the user, in the order of the stops. */
    @Transactional(readOnly = true)
    public List<RouteSchedule> ofTrip(long appUserId, long tripId) {
        return tripLogic.routes(appUserId, tripId).stream().map(this::schedule).toList();
    }

    private RouteSchedule schedule(Route route) {
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(route.getId());
        return new RouteSchedule(route.getId(), route.getTrip().getId(),
                sections.isEmpty() ? null : sections.getFirst().getExpectedPassageStart(),
                sections.isEmpty() ? null : sections.getLast().getExpectedPassageEnd(),
                route.getToStop().getArrivalAt());
    }
}
