package com.nfv.recommendations.logic;

import com.nfv.recommendations.data.SuggestionSetRepository;
import com.nfv.recommendations.entities.OptionKind;
import com.nfv.recommendations.entities.RouteOption;
import com.nfv.recommendations.entities.RouteOptionPoint;
import com.nfv.recommendations.entities.SuggestionSet;
import com.nfv.trips.entities.WaypointOrigin;
import com.nfv.trips.logic.NewWaypoint;
import com.nfv.trips.logic.PlanResult;
import com.nfv.trips.logic.RouteSnapshot;
import com.nfv.trips.logic.TripEditing;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Applies a suggestion: its via points become the waypoints of the route, which is planned again via
 * {@link TripEditing}, see Docs/recommendations.md#übernehmen-eines-vorschlags.
 */
@Service
@RequiredArgsConstructor
public class SuggestionApplication {
    private final Suggestions suggestions;
    private final SuggestionSetRepository setRepository;
    private final TripEditing tripEditing;
    private final TransactionTemplate transactions;
    private final Clock clock;

    private record Change(long tripId, long routeId, List<NewWaypoint> waypoints) {
    }

    /** @param result outcome of planning the route, {@code null} if it was deleted meanwhile */
    public record Applied(long tripId, long routeId, @Nullable PlanResult result) {
    }

    /**
     * Replaces the waypoints of the route (with {@code FOLLOW} the waypoints of the user stay with their id, with
     * {@code IGNORE} they are removed), plans the route and marks the set {@code APPLIED}. If planning fails the
     * waypoints stay anyway (rule of {@link TripEditing}), the route shows the outcome.
     *
     * @throws SuggestionSetNotFoundException set or option unknown or of another user
     * @throws SuggestionStaleException       set no longer {@code CURRENT}
     */
    public Applied apply(long appUserId, long setId, long optionId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Change change = Objects.requireNonNull(transactions.execute(status -> {
            SuggestionSet set = setRepository.findById(setId)
                    .orElseThrow(() -> new SuggestionSetNotFoundException(setId));
            RouteSnapshot route = suggestions.ownedRoute(appUserId, set);
            RouteOption option = option(set, optionId);
            if (!set.isCurrent(now)) {
                throw new SuggestionStaleException(setId);
            }
            if (option.getKind() != OptionKind.ALTERNATIVES) {
                throw new IllegalStateException("applying " + option.getKind() + " is not supported yet");
            }
            return new Change(route.tripId(), route.routeId(), waypoints(option, route));
        }));
        Map<Long, PlanResult> results = tripEditing.replaceWaypoints(appUserId, change.tripId(), change.routeId(),
                change.waypoints());
        transactions.executeWithoutResult(status -> {
            SuggestionSet set = setRepository.findById(setId)
                    .orElseThrow(() -> new SuggestionSetNotFoundException(setId));
            set.applied(option(set, optionId), OffsetDateTime.now(clock));
        });
        return new Applied(change.tripId(), change.routeId(), results.get(change.routeId()));
    }

    /** Points of the option as ordered waypoints: existing ones keep id, coordinate and name of the user. */
    private static List<NewWaypoint> waypoints(RouteOption option, RouteSnapshot route) {
        Map<Long, RouteSnapshot.WaypointPoint> existing = route.waypoints().stream()
                .collect(Collectors.toMap(RouteSnapshot.WaypointPoint::id, Function.identity()));
        return option.getPoints().stream()
                .map(point -> {
                    RouteSnapshot.WaypointPoint kept = point.getWaypointId() == null ? null
                            : existing.get(point.getWaypointId());
                    return kept != null
                            ? new NewWaypoint(kept.id(), kept.latitude(), kept.longitude(), kept.name(),
                            kept.roadHint(), kept.origin())
                            : suggested(point);
                })
                .toList();
    }

    private static NewWaypoint suggested(RouteOptionPoint point) {
        return new NewWaypoint(null, point.getLatitude(), point.getLongitude(), null, point.getRoadHint(),
                WaypointOrigin.SUGGESTION);
    }

    private static RouteOption option(SuggestionSet set, long optionId) {
        return set.getOptions().stream().filter(o -> o.getId() == optionId).findFirst()
                .orElseThrow(() -> new SuggestionSetNotFoundException(set.getId()));
    }
}