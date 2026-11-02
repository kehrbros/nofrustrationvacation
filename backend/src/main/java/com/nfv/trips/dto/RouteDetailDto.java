package com.nfv.trips.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Route with its waypoints in order")
public record RouteDetailDto(RouteDto route, List<WaypointDto> waypoints) {
}