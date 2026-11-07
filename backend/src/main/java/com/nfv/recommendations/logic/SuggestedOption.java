package com.nfv.recommendations.logic;

import com.nfv.incidents.entities.Impact;
import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.RoutedSegment;
import jakarta.annotation.Nullable;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * One route suggestion, computed per request and not stored. Only the option the user chooses is stored, as
 * waypoints of the route, see Docs/recommendations.md#auswahl-speichern.
 *
 * @param rank        1 is the best
 * @param via         main road of the part that differs from the fastest way, e.g. {@code SH 2}
 * @param arrivalAt   arrival at the end stop including waiting and delays
 * @param duration    driving time without waiting and delays
 * @param sharedRatio part of the distance shared with the planned route, {@code null} without one
 * @param geometry    line for the map
 * @param points      via points that become the waypoints of the route when the option is chosen
 * @param conflicts   incidents still passed on this way
 * @param path        driven segments in order, for {@link Stopovers}; not part of the API
 */
public record SuggestedOption(int rank, OptionKind kind, Set<OptionLabel> labels, @Nullable String via,
                              OffsetDateTime departureAt, OffsetDateTime arrivalAt, double distanceMeters,
                              Duration duration, Duration waiting, Duration delay, @Nullable Double sharedRatio,
                              List<GeoPoint> geometry, List<Point> points, List<Conflict> conflicts,
                              List<RoutedSegment> path) {

    /** @param waypointId existing waypoint of the user that this point keeps ({@code FOLLOW}) */
    public record Point(double latitude, double longitude, @Nullable String roadHint, @Nullable Long waypointId) {
    }

    public record Conflict(long incidentId, Impact impact, @Nullable Duration delay) {
    }
}
