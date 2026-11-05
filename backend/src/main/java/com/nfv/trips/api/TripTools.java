package com.nfv.trips.api;

import com.nfv.trips.dto.RouteDetailDto;
import com.nfv.trips.dto.TripDetailDto;
import com.nfv.trips.dto.TripSummaryDto;
import com.nfv.trips.dto.WaypointInputDto;
import com.nfv.trips.logic.NewStop;
import com.nfv.trips.logic.NewWaypoint;
import com.nfv.trips.logic.TripEditing;
import com.nfv.trips.logic.TripLogic;
import com.nfv.user.security.CurrentUser;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * MCP tools for the trips of the authenticated user, same logic as the REST API. Read tools are listed in
 * {@code nfv.mcp.read-tools}, all others need the scope {@code mcp:write}. No tool deletes a trip.
 */
@Component
@RequiredArgsConstructor
public class TripTools {
    private static final String TRAVELERS = "Number of travelers (1-99), suggested as guests of bookings.";
    private static final String TIME = "Point in time with offset, e.g. 2027-07-12T08:00:00+12:00";
    private static final String PLANNING = " Routes are planned right away; planning needs coordinates of both stops "
            + "and a departure at the start stop, see planOutcome and planReason of the routes.";

    private final TripLogic tripLogic;
    private final TripEditing tripEditing;
    private final TripDtos tripDtos;

    @McpTool(name = "list_trips", description = "All trips of the user, ascending by start, with their phase.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public List<TripSummaryDto> listTrips() {
        return tripDtos.list(appUserId());
    }

    @McpTool(name = "get_trip", description = "Trip with its stops in order and the routes between them.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public TripDetailDto getTrip(@McpToolParam(description = "Id of the trip") long tripId) {
        return tripDtos.detail(appUserId(), tripId);
    }

    @McpTool(name = "get_route", description = "Route between two stops with its waypoints and planning status.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public RouteDetailDto getRoute(@McpToolParam(description = "Id of the trip") long tripId,
                                   @McpToolParam(description = "Id of the route") long routeId) {
        return tripDtos.routeDetail(appUserId(), tripId, routeId);
    }

    @McpTool(name = "create_trip", description = "Creates a trip without stops.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    public TripDetailDto createTrip(@McpToolParam(description = "Name of the trip") String name,
                                    @McpToolParam(description = "Begin of the trip. " + TIME) OffsetDateTime startAt,
                                    @McpToolParam(description = "End of the trip, inclusive. " + TIME)
                                    OffsetDateTime endAt,
                                    @McpToolParam(description = TRAVELERS, required = false) @Nullable Integer travelers) {
        long appUserId = appUserId();
        return tripDtos.detail(appUserId, tripLogic.create(appUserId, name, startAt, endAt, travelers).getId());
    }

    @McpTool(name = "update_trip", description = "Changes name, period and number of travelers of a trip.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public TripDetailDto updateTrip(@McpToolParam(description = "Id of the trip") long tripId,
                                    @McpToolParam(description = "Name of the trip") String name,
                                    @McpToolParam(description = "Begin of the trip. " + TIME) OffsetDateTime startAt,
                                    @McpToolParam(description = "End of the trip, inclusive. " + TIME)
                                    OffsetDateTime endAt,
                                    @McpToolParam(description = TRAVELERS + " Omitted: removes the number.",
                                            required = false) @Nullable Integer travelers) {
        long appUserId = appUserId();
        tripLogic.update(appUserId, tripId, name, startAt, endAt, travelers);
        return tripDtos.detail(appUserId, tripId);
    }

    @McpTool(name = "add_stop", description = "Inserts a stop into a trip, at the end without position." + PLANNING,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    public TripDetailDto addStop(
            @McpToolParam(description = "Id of the trip") long tripId,
            @McpToolParam(description = "Name of the place") String name,
            @McpToolParam(description = "Latitude (WGS 84)", required = false) @Nullable Double latitude,
            @McpToolParam(description = "Longitude (WGS 84)", required = false) @Nullable Double longitude,
            @McpToolParam(description = "Arrival at the stop. " + TIME, required = false)
            @Nullable OffsetDateTime arrivalAt,
            @McpToolParam(description = "Departure from the stop. " + TIME, required = false)
            @Nullable OffsetDateTime departureAt,
            @McpToolParam(description = "Where to insert, 0 to number of stops; omit to append", required = false)
            @Nullable Integer position) {
        long appUserId = appUserId();
        tripEditing.addStop(appUserId, tripId, new NewStop(name, latitude, longitude, arrivalAt, departureAt),
                position);
        return tripDtos.detail(appUserId, tripId);
    }

    @McpTool(name = "update_stop", description = "Replaces all fields of a stop." + PLANNING,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public TripDetailDto updateStop(
            @McpToolParam(description = "Id of the trip") long tripId,
            @McpToolParam(description = "Id of the stop") long stopId,
            @McpToolParam(description = "Name of the place") String name,
            @McpToolParam(description = "Latitude (WGS 84)", required = false) @Nullable Double latitude,
            @McpToolParam(description = "Longitude (WGS 84)", required = false) @Nullable Double longitude,
            @McpToolParam(description = "Arrival at the stop. " + TIME, required = false)
            @Nullable OffsetDateTime arrivalAt,
            @McpToolParam(description = "Departure from the stop. " + TIME, required = false)
            @Nullable OffsetDateTime departureAt) {
        long appUserId = appUserId();
        tripEditing.updateStop(appUserId, tripId, stopId,
                new NewStop(name, latitude, longitude, arrivalAt, departureAt));
        return tripDtos.detail(appUserId, tripId);
    }

    @McpTool(name = "move_stop", description = "Moves a stop to another position in the trip." + PLANNING,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public TripDetailDto moveStop(@McpToolParam(description = "Id of the trip") long tripId,
                                  @McpToolParam(description = "Id of the stop") long stopId,
                                  @McpToolParam(description = "New position, 0 to number of stops - 1") int position) {
        long appUserId = appUserId();
        tripEditing.moveStop(appUserId, tripId, stopId, position);
        return tripDtos.detail(appUserId, tripId);
    }

    @McpTool(name = "remove_stop", description = "Deletes a stop with its bookings and the routes and "
            + "waypoints to and from it; its neighbours get a new route." + PLANNING,
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = false,
                    openWorldHint = false))
    public TripDetailDto removeStop(@McpToolParam(description = "Id of the trip") long tripId,
                                    @McpToolParam(description = "Id of the stop") long stopId) {
        long appUserId = appUserId();
        tripEditing.removeStop(appUserId, tripId, stopId);
        return tripDtos.detail(appUserId, tripId);
    }

    @McpTool(name = "set_waypoints", description = "Replaces the waypoints of a route by the ordered list: with id an "
            + "existing waypoint is changed, without id one is created, missing ones are deleted. The route is "
            + "planned again.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true,
                    openWorldHint = false))
    public RouteDetailDto setWaypoints(@McpToolParam(description = "Id of the trip") long tripId,
                                       @McpToolParam(description = "Id of the route") long routeId,
                                       @McpToolParam(description = "All waypoints of the route in order")
                                       List<WaypointInputDto> waypoints) {
        long appUserId = appUserId();
        tripEditing.replaceWaypoints(appUserId, tripId, routeId, waypoints.stream()
                .map(w -> new NewWaypoint(w.id(), w.latitude(), w.longitude(), w.name(), w.roadHint()))
                .toList());
        return tripDtos.routeDetail(appUserId, tripId, routeId);
    }

    @McpTool(name = "plan_route", description = "Plans a route again without changes, e.g. after "
            + "ROUTING_UNAVAILABLE.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true,
                    openWorldHint = false))
    public RouteDetailDto planRoute(@McpToolParam(description = "Id of the trip") long tripId,
                                    @McpToolParam(description = "Id of the route") long routeId) {
        long appUserId = appUserId();
        tripEditing.plan(appUserId, tripId, routeId);
        return tripDtos.routeDetail(appUserId, tripId, routeId);
    }

    /** The user of the MCP request, authenticated by {@code McpSecurityConfig}. */
    private static long appUserId() {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof CurrentUser user) {
            return user.appUserId();
        }
        throw new IllegalStateException("no authenticated user");
    }
}