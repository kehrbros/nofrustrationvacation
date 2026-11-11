package com.nfv.recommendations.logic;

import com.nfv.incidents.logic.ActiveIncidents;
import com.nfv.incidents.logic.RouteConsequence;
import com.nfv.network.logic.GeoPoint;
import com.nfv.network.logic.PlaceKind;
import com.nfv.network.logic.Places;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.network.logic.SegmentQueries;
import com.nfv.trips.logic.PlannedSection;
import com.nfv.trips.logic.RouteSnapshot;
import com.nfv.trips.logic.RouteSnapshots;
import com.nfv.trips.logic.TripItemNotFoundException;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Another target when the planned one cannot be reached: places around it that can be reached, see
 * Docs/incident-plans.md#rules. Close to the planned target (the holiday that was planned) and to the next stop (the
 * rest of the trip stays possible), towns before villages. Computed per request, nothing stored, no transaction.
 */
@Service
@RequiredArgsConstructor
public class Destinations {
    /** Around the planned target. */
    static final int RADIUS_METERS = 80_000;
    /** The planned target itself (and its suburbs) is no other target. */
    static final int MIN_DISTANCE_METERS = 5_000;
    /** Places routed at most, each one costs a few routings. */
    static final int MAX_CANDIDATES = 6;
    static final int MAX_RESULTS = 5;

    private final RouteSnapshots routeSnapshots;
    private final ActiveIncidents activeIncidents;
    private final SegmentQueries segmentQueries;
    private final Places places;
    private final Drives drives;
    private final Clock clock;

    /**
     * @param distanceToTargetMeters straight line to the planned target
     * @param distanceToNextMeters   straight line to the stop after the target, {@code null} without one
     * @param drive                  the way there from the start (or the estimated position) of the route
     */
    public record Destination(String name, PlaceKind kind, double latitude, double longitude,
                              double distanceToTargetMeters, @Nullable Double distanceToNextMeters,
                              SuggestedOption drive) {
    }

    /** @param problem {@code ROUTE_NOT_USABLE} or {@code NOTHING_REACHABLE} if empty */
    public record Found(List<Destination> destinations, @Nullable String problem) {
    }

    /**
     * Places around the target of the route of the consequence that are reached by {@code latestArrival}.
     *
     * @param next          the stop after the target, {@code null} if it is the last
     * @param latestArrival {@code null}: no limit
     * @throws TripItemNotFoundException       consequence unknown or on a route of another user
     * @throws InvalidSuggestionInputException number out of range
     */
    public Found around(long appUserId, long consequenceId, @Nullable GeoPoint next,
                        @Nullable OffsetDateTime latestArrival, int maxResults) {
        if (maxResults < 1 || maxResults > MAX_RESULTS) {
            throw new InvalidSuggestionInputException("max_results must be between 1 and " + MAX_RESULTS);
        }
        RouteConsequence consequence = activeIncidents.consequence(consequenceId)
                .orElseThrow(() -> new TripItemNotFoundException("consequence", consequenceId));
        RouteSnapshot route = routeSnapshots.of(appUserId, consequence.routeId());
        Map<Long, SegmentInfo> segments = segmentQueries.segments(
                route.sections().stream().map(PlannedSection::segmentId).collect(Collectors.toSet()));
        ScenarioFactory.Built built = ScenarioFactory.forConsequence(route, consequence,
                activeIncidents.consequencesOnRoute(route.routeId()), Set.of(OptionKind.ALTERNATIVES), 1, null,
                OffsetDateTime.now(clock), segments);
        if (built.problem() != null) {
            return new Found(List.of(), "ROUTE_NOT_USABLE");
        }
        Scenario scenario = Objects.requireNonNull(built.scenario());
        GeoPoint target = scenario.end().point();
        List<Places.PlaceAround> candidates = places.around(target, RADIUS_METERS, 60).stream()
                .filter(place -> place.distanceMeters() >= MIN_DISTANCE_METERS)
                .sorted(Comparator.comparingDouble(place -> score(place, next)))
                .limit(MAX_CANDIDATES)
                .toList();
        List<Destination> found = new ArrayList<>();
        for (Places.PlaceAround place : candidates) {
            Drives.Drive drive = drives.between(scenario.start().point(), place.point(), scenario.departure());
            SuggestedOption option = drive.option();
            if (option == null || latestArrival != null && option.arrivalAt().isAfter(latestArrival)) {
                continue;
            }
            found.add(new Destination(place.name(), place.kind(), place.point().lat(), place.point().lon(),
                    place.distanceMeters(), next == null ? null : meters(place.point(), next), option));
            if (found.size() == maxResults) {
                break;
            }
        }
        return new Found(found, found.isEmpty() ? "NOTHING_REACHABLE" : null);
    }

    /**
     * Lower is better: distance to the target, plus half the distance to the next stop, a village counts 20 km more
     * than a town (fewer accommodations, less to do).
     */
    static double score(Places.PlaceAround place, @Nullable GeoPoint next) {
        double score = place.distanceMeters();
        if (next != null) {
            score += meters(place.point(), next) / 2;
        }
        return place.kind() == PlaceKind.VILLAGE ? score + 20_000 : score;
    }

    /** Straight line, good enough to order places. */
    static double meters(GeoPoint a, GeoPoint b) {
        double cos = Math.cos(Math.toRadians((a.lat() + b.lat()) / 2));
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon()) * cos;
        return 6_371_000 * Math.hypot(dLat, dLon);
    }
}
