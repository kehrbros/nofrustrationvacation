package com.nfv.recommendations.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "The chosen suggestion: its waypoints as returned with it, empty for the fastest way without "
        + "waypoints")
public record RouteChoiceDto(List<ChoicePointDto> waypoints) {
}
