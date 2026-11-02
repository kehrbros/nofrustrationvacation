package com.nfv.trips.dto;

import com.nfv.trips.entities.WaypointOrigin;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

@Schema(description = "Point the user wants to pass between the two stops of a route")
public record WaypointDto(long id, int position, double latitude, double longitude, @Nullable String name,
                          @Schema(description = "Road the point should snap to, e.g. SH 73") @Nullable String roadHint,
                          @Schema(description = "USER: chosen by the user, SUGGESTION: created by applying a route "
                                  + "suggestion") WaypointOrigin origin) {
}