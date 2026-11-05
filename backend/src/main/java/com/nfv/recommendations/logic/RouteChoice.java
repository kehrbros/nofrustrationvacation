package com.nfv.recommendations.logic;

import com.nfv.recommendations.config.RecommendationProperties;
import com.nfv.trips.entities.WaypointOrigin;
import com.nfv.trips.logic.NewWaypoint;
import com.nfv.trips.logic.PlanResult;
import com.nfv.trips.logic.RouteSnapshot;
import com.nfv.trips.logic.RouteSnapshots;
import com.nfv.trips.logic.TripEditing;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stores the suggestion the user chose: its points become the waypoints of the route, which is planned again via
 * {@link TripEditing}. Suggestions are not stored, the client sends the points of the chosen option back, see
 * Docs/recommendations.md#auswahl-speichern. No check for concurrent changes yet, the last choice wins.
 */
@Service
@RequiredArgsConstructor
public class RouteChoice {
    private final RouteSnapshots routeSnapshots;
    private final TripEditing tripEditing;
    private final RecommendationProperties properties;

    /** @param result outcome of planning the route, {@code null} if it was deleted meanwhile */
    public record Chosen(long tripId, long routeId, @Nullable PlanResult result) {
    }

    /**
     * Replaces the waypoints of the route by the points of the chosen option: points with a {@code waypointId} of
     * the route keep that waypoint (id, coordinate, name and origin of the user), all others become waypoints with
     * origin {@code SUGGESTION}. Waypoints not in the list are removed. If planning fails the waypoints stay anyway
     * (rule of {@link TripEditing}), the route shows the outcome.
     *
     * @throws com.nfv.trips.logic.TripItemNotFoundException route unknown or of another user
     * @throws InvalidSuggestionInputException               too many points, invalid coordinate or unknown waypoint
     */
    public Chosen choose(long appUserId, long routeId, List<SuggestedOption.Point> points) {
        if (points.size() > properties.maxChosenPoints()) {
            throw new InvalidSuggestionInputException("at most " + properties.maxChosenPoints() + " points");
        }
        RouteSnapshot route = routeSnapshots.of(appUserId, routeId);
        Map<Long, RouteSnapshot.WaypointPoint> existing = route.waypoints().stream()
                .collect(Collectors.toMap(RouteSnapshot.WaypointPoint::id, Function.identity()));
        List<NewWaypoint> waypoints = points.stream().map(point -> waypoint(point, existing)).toList();
        Map<Long, PlanResult> results = tripEditing.replaceWaypoints(appUserId, route.tripId(), routeId, waypoints);
        return new Chosen(route.tripId(), routeId, results.get(routeId));
    }

    private static NewWaypoint waypoint(SuggestedOption.Point point, Map<Long, RouteSnapshot.WaypointPoint> existing) {
        if (point.waypointId() != null) {
            RouteSnapshot.WaypointPoint kept = existing.get(point.waypointId());
            if (kept == null) {
                throw new InvalidSuggestionInputException("waypoint " + point.waypointId() + " is not on the route");
            }
            return new NewWaypoint(kept.id(), kept.latitude(), kept.longitude(), kept.name(), kept.roadHint(),
                    kept.origin());
        }
        if (!(Math.abs(point.latitude()) <= 90) || !(Math.abs(point.longitude()) <= 180)) {
            throw new InvalidSuggestionInputException("invalid coordinate");
        }
        String hint = point.roadHint() == null || point.roadHint().length() <= 255 ? point.roadHint()
                : point.roadHint().substring(0, 255);
        return new NewWaypoint(null, point.latitude(), point.longitude(), null, hint, WaypointOrigin.SUGGESTION);
    }
}
