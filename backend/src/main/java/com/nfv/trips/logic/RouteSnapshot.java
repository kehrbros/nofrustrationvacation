package com.nfv.trips.logic;

import com.nfv.trips.entities.WaypointOrigin;
import jakarta.annotation.Nullable;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A route as other modules read it (recommendations), without entities: stops, waypoints and planned sections, read
 * in one transaction by {@link RouteSnapshots}.
 *
 * @param sections empty if the route is not planned (yet) or its sections were discarded
 */
public record RouteSnapshot(long routeId, long tripId, StopPoint from, StopPoint to, List<WaypointPoint> waypoints,
                            List<PlannedSection> sections) {
    public record StopPoint(long id, String name, @Nullable Double latitude, @Nullable Double longitude,
                            @Nullable OffsetDateTime arrivalAt, @Nullable OffsetDateTime departureAt) {
        public boolean hasCoordinates() {
            return latitude != null && longitude != null;
        }
    }

    public record WaypointPoint(long id, double latitude, double longitude, @Nullable String name,
                                @Nullable String roadHint, WaypointOrigin origin) {
    }
}