package com.vacantagent.trips.api;

import com.vacantagent.trips.dto.RouteDetailDto;
import com.vacantagent.trips.dto.WaypointInputDto;
import com.vacantagent.trips.logic.NewWaypoint;
import com.vacantagent.trips.logic.TripEditing;
import com.vacantagent.user.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Routes", description = "Routes between the stops of a trip and their waypoints")
@RestController
@RequestMapping("/api/v1/trips/{tripId}/routes")
@RequiredArgsConstructor
public class RouteController {
    private final TripEditing tripEditing;
    private final TripDtos tripDtos;

    @Operation(summary = "Route with waypoints")
    @GetMapping("/{routeId}")
    public RouteDetailDto getRoute(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId,
                                   @PathVariable long routeId) {
        return tripDtos.routeDetail(user.appUserId(), tripId, routeId);
    }

    @Operation(summary = "Replace the waypoints", description = "Ordered list: with id changed, without id created, "
            + "missing ones deleted. The route is planned again.")
    @PutMapping("/{routeId}/waypoints")
    public RouteDetailDto replaceWaypoints(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId,
                                           @PathVariable long routeId, @RequestBody List<WaypointInputDto> waypoints) {
        tripEditing.replaceWaypoints(user.appUserId(), tripId, routeId, waypoints.stream()
                .map(w -> new NewWaypoint(w.id(), w.latitude(), w.longitude(), w.name(), w.roadHint()))
                .toList());
        return tripDtos.routeDetail(user.appUserId(), tripId, routeId);
    }

    @Operation(summary = "Plan the route again", description = "E.g. after ROUTING_UNAVAILABLE, see planOutcome.")
    @PostMapping("/{routeId}/plan")
    public RouteDetailDto plan(@AuthenticationPrincipal CurrentUser user, @PathVariable long tripId,
                               @PathVariable long routeId) {
        tripEditing.plan(user.appUserId(), tripId, routeId);
        return tripDtos.routeDetail(user.appUserId(), tripId, routeId);
    }
}