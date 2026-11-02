package com.vacantagent.trips.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "New position of a stop, 0 to number of stops - 1")
public record MoveStopDto(int position) {
}