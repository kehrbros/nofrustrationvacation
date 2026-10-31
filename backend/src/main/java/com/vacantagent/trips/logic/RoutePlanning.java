package com.vacantagent.trips.logic;

import com.vacantagent.network.logic.GeoPoint;
import com.vacantagent.network.logic.NoRouteException;
import com.vacantagent.network.logic.RouteOptions;
import com.vacantagent.network.logic.RoutePoint;
import com.vacantagent.network.logic.RouteResult;
import com.vacantagent.network.logic.RoutedSegment;
import com.vacantagent.network.logic.Routing;
import com.vacantagent.network.logic.RoutingUnavailableException;
import com.vacantagent.trips.data.RouteRepository;
import com.vacantagent.trips.data.WaypointRepository;
import com.vacantagent.trips.entities.Route;
import com.vacantagent.trips.entities.Stop;
import jakarta.annotation.Nullable;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Plans a route from its stops and waypoints via {@link Routing}, without incidents (detours are recommendations),
 * see Docs/domain.md#trips.
 */
@Service
@RequiredArgsConstructor
public class RoutePlanning {
    private final RouteRepository routeRepository;
    private final WaypointRepository waypointRepository;
    private final Routing routing;
    private final RouteLogic routeLogic;
    private final TransactionTemplate transactions;

    /**
     * Replaces the sections of the route and publishes {@code RouteChanged}, see {@link RouteLogic#replaceSections}.
     * Routing runs outside of the transaction.
     *
     * @throws RoutingUnavailableException no road network loaded yet, try again later
     */
    public PlanResult plan(long routeId) {
        Request request = transactions.execute(status -> request(routeId));
        if (request.problem() != null) {
            return request.problem();
        }
        RouteResult result;
        try {
            result = routing.route(request.points(), RouteOptions.none());
        } catch (NoRouteException e) {
            return new PlanResult(PlanResult.Outcome.NO_ROUTE, e.getMessage());
        }
        routeLogic.replaceSections(routeId, sections(result, request.departure()));
        return new PlanResult(PlanResult.Outcome.PLANNED, null);
    }

    /** Stops and waypoints of the route as routing points, read in one transaction. */
    private record Request(List<RoutePoint> points, @Nullable OffsetDateTime departure,
                           @Nullable PlanResult problem) {
    }

    private Request request(long routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new EntityNotFoundException("route " + routeId));
        Stop from = route.getFromStop();
        Stop to = route.getToStop();
        if (from.getLatitude() == null || from.getLongitude() == null
                || to.getLatitude() == null || to.getLongitude() == null) {
            return new Request(List.of(), null, new PlanResult(PlanResult.Outcome.MISSING_COORDINATES,
                    "stop without coordinates"));
        }
        if (from.getDepartureAt() == null) {
            return new Request(List.of(), null, new PlanResult(PlanResult.Outcome.MISSING_DEPARTURE,
                    "no departure at " + from.getName()));
        }
        List<RoutePoint> points = new ArrayList<>();
        points.add(new RoutePoint(new GeoPoint(from.getLatitude(), from.getLongitude()), RoutePoint.Kind.STOP, null));
        waypointRepository.findByRouteIdOrderByPosition(routeId).forEach(waypoint -> points.add(new RoutePoint(
                new GeoPoint(waypoint.getLatitude(), waypoint.getLongitude()), RoutePoint.Kind.VIA,
                waypoint.getRoadHint())));
        points.add(new RoutePoint(new GeoPoint(to.getLatitude(), to.getLongitude()), RoutePoint.Kind.STOP, null));
        return new Request(points, from.getDepartureAt(), null);
    }

    /** Passage times: departure plus the durations of all segments before. */
    private static List<PlannedSection> sections(RouteResult result, OffsetDateTime departure) {
        List<PlannedSection> sections = new ArrayList<>(result.segments().size());
        OffsetDateTime passage = departure;
        for (RoutedSegment segment : result.segments()) {
            OffsetDateTime end = passage.plus(segment.duration());
            sections.add(new PlannedSection(segment.segmentId(), segment.fromFraction(), segment.toFraction(),
                    passage, end));
            passage = end;
        }
        return sections;
    }
}