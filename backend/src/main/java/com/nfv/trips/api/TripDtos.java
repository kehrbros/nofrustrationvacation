package com.nfv.trips.api;

import com.nfv.trips.dto.RouteDetailDto;
import com.nfv.trips.dto.RouteDto;
import com.nfv.trips.dto.StopDto;
import com.nfv.trips.dto.TripDetailDto;
import com.nfv.trips.dto.TripSummaryDto;
import com.nfv.trips.dto.WaypointDto;
import com.nfv.trips.entities.Route;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.entities.Trip;
import com.nfv.trips.entities.Waypoint;
import com.nfv.trips.logic.TripLogic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/** Reads trips and routes as DTOs, shared by the REST controllers and the MCP tools. */
@Component
@RequiredArgsConstructor
class TripDtos {
    private final TripLogic tripLogic;
    private final Clock clock;

    List<TripSummaryDto> list(long appUserId) {
        return tripLogic.list(appUserId).stream()
                .map(overview -> new TripSummaryDto(overview.trip().getId(), overview.trip().getName(),
                        overview.trip().getStartAt(), overview.trip().getEndAt(), overview.phase(), overview.stopCount(),
                        overview.nextStop() == null ? null : overview.nextStop().getName(), overview.hasCoverImage()))
                .toList();
    }

    TripDetailDto detail(long appUserId, long tripId) {
        Trip trip = tripLogic.get(appUserId, tripId);
        return new TripDetailDto(trip.getId(), trip.getName(), trip.getStartAt(), trip.getEndAt(),
                trip.phaseAt(OffsetDateTime.now(clock)), tripLogic.coverImage(appUserId, tripId).isPresent(),
                tripLogic.stops(appUserId, tripId).stream().map(TripDtos::stop).toList(),
                tripLogic.routes(appUserId, tripId).stream().map(TripDtos::route).toList());
    }

    RouteDetailDto routeDetail(long appUserId, long tripId, long routeId) {
        return new RouteDetailDto(route(tripLogic.route(appUserId, tripId, routeId)),
                tripLogic.waypoints(appUserId, tripId, routeId).stream().map(TripDtos::waypoint).toList());
    }

    static StopDto stop(Stop stop) {
        return new StopDto(stop.getId(), stop.getPosition(), stop.getName(), stop.getLatitude(), stop.getLongitude(),
                stop.getArrivalAt(), stop.getDepartureAt());
    }

    static RouteDto route(Route route) {
        return new RouteDto(route.getId(), route.getFromStop().getId(), route.getToStop().getId(), route.getPlannedAt(),
                route.getPlanOutcome(), route.getPlanReason());
    }

    static WaypointDto waypoint(Waypoint waypoint) {
        return new WaypointDto(waypoint.getId(), waypoint.getPosition(), waypoint.getLatitude(),
                waypoint.getLongitude(), waypoint.getName(), waypoint.getRoadHint(), waypoint.getOrigin());
    }
}