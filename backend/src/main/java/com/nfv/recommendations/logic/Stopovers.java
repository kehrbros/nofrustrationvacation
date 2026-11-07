package com.nfv.recommendations.logic;

import com.nfv.incidents.logic.ActiveIncidents;
import com.nfv.incidents.logic.RouteConsequence;
import com.nfv.network.logic.PlaceKind;
import com.nfv.network.logic.Places;
import com.nfv.network.logic.RouteOptions;
import com.nfv.network.logic.RoutedSegment;
import com.nfv.network.logic.SectionPart;
import com.nfv.network.logic.SegmentInfo;
import com.nfv.network.logic.SegmentQueries;
import com.nfv.recommendations.config.RecommendationProperties;
import com.nfv.trips.logic.PlannedSection;
import com.nfv.trips.logic.RouteSnapshot;
import com.nfv.trips.logic.RouteSnapshots;
import com.nfv.trips.logic.TripItemNotFoundException;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Stays along a route that cannot be driven in time, see Docs/advisor.md#find_overnight_areas. Computed per request
 * and not stored, without the model and outside of transactions.
 */
@Service
@RequiredArgsConstructor
public class Stopovers {
    private final RouteSnapshots routeSnapshots;
    private final ActiveIncidents activeIncidents;
    private final SegmentQueries segmentQueries;
    private final Places places;
    private final RecommendationProperties properties;
    private final Clock clock;

    /**
     * @param areas closest to the obstacle or the end of the way first, empty if there is a {@code problem}
     */
    public record OvernightAreas(List<OvernightArea> areas, @Nullable StopoverProblem problem) {
        static OvernightAreas problem(StopoverProblem problem) {
            return new OvernightAreas(List.of(), problem);
        }
    }

    /**
     * @param arrivalAt       at the closest point of the way, without the way off it
     * @param remainingMeters from that point to the end of the way
     * @param offRouteMeters  straight line from the way, the drive can be longer
     */
    public record OvernightArea(String name, PlaceKind kind, double latitude, double longitude,
                                OffsetDateTime arrivalAt, double remainingMeters, double offRouteMeters) {
    }

    public enum StopoverProblem {
        /** No place before the obstacle in time. */
        NOTHING_REACHABLE,
        /** The way has no obstacle and reaches the target stop in time, no stay needed. */
        ARRIVES_BEFORE_LIMIT,
        /** The consequence is passed or the route has no plan, coordinates or usable way. */
        ROUTE_NOT_USABLE
    }

    /**
     * Places on the planned route before the first closure or the consequence, whichever comes first.
     *
     * @param latestArrival {@code null}: the evening limit of the region after the departure
     * @param maxResults    {@code null}: default
     * @throws TripItemNotFoundException       consequence unknown or on a route of another user
     * @throws InvalidSuggestionInputException number out of range or limit not after the departure
     */
    public OvernightAreas onPlannedRoute(long appUserId, long consequenceId, @Nullable OffsetDateTime latestArrival,
                                         @Nullable Integer maxResults) {
        int wanted = wanted(maxResults);
        Situation situation = situation(appUserId, consequenceId);
        if (situation.built().problem() != null) {
            return OvernightAreas.problem(StopoverProblem.ROUTE_NOT_USABLE);
        }
        Scenario scenario = Objects.requireNonNull(situation.built().scenario());
        List<SectionPart> parts = scenario.reference().stream()
                .map(s -> new SectionPart(s.segmentId(), s.fromFraction(), s.toFraction())).toList();
        return find(SuggestionEngine.steps(scenario.reference(), situation.segments()), parts, scenario.departure(),
                situation.consequence().segmentIds(), latestArrival, wanted);
    }

    /**
     * Places on another way, before the first closure still on it.
     *
     * @param path          {@link SuggestedOption#path()}
     * @param departure     {@link SuggestedOption#departureAt()}
     * @param latestArrival {@code null}: the evening limit of the region after the departure
     * @param maxResults    {@code null}: default
     * @throws TripItemNotFoundException       consequence unknown or on a route of another user
     * @throws InvalidSuggestionInputException number out of range or limit not after the departure
     */
    public OvernightAreas onOption(long appUserId, long consequenceId, List<RoutedSegment> path,
                                   OffsetDateTime departure, @Nullable OffsetDateTime latestArrival,
                                   @Nullable Integer maxResults) {
        int wanted = wanted(maxResults);
        if (situation(appUserId, consequenceId).built().problem() != null || path.isEmpty()) {
            return OvernightAreas.problem(StopoverProblem.ROUTE_NOT_USABLE);
        }
        List<SectionPart> parts = path.stream()
                .map(s -> new SectionPart(s.segmentId(), s.fromFraction(), s.toFraction())).toList();
        return find(ViaPointExtractor.steps(path), parts, departure, Set.of(), latestArrival, wanted);
    }

    private record Situation(RouteConsequence consequence, ScenarioFactory.Built built,
                             Map<Long, SegmentInfo> segments) {
    }

    /** Checks the owner and, once the route has started, gives what is still ahead from the estimated position. */
    private Situation situation(long appUserId, long consequenceId) {
        RouteConsequence consequence = activeIncidents.consequence(consequenceId)
                .orElseThrow(() -> new TripItemNotFoundException("consequence", consequenceId));
        RouteSnapshot route = routeSnapshots.of(appUserId, consequence.routeId());
        Map<Long, SegmentInfo> segments = segmentQueries.segments(
                route.sections().stream().map(PlannedSection::segmentId).collect(Collectors.toSet()));
        ScenarioFactory.Built built = ScenarioFactory.forConsequence(route, consequence,
                activeIncidents.consequencesOnRoute(route.routeId()), Set.of(OptionKind.ALTERNATIVES), 1, null,
                OffsetDateTime.now(clock), segments);
        return new Situation(consequence, built, segments);
    }

    /** @param obstacleSegmentIds cannot be passed in any case, in addition to the closures on the way */
    private OvernightAreas find(List<CandidateEvaluator.Step> steps, List<SectionPart> parts,
                                OffsetDateTime departure, Set<Long> obstacleSegmentIds,
                                @Nullable OffsetDateTime latestArrival, int wanted) {
        OffsetDateTime limit = latestArrival != null ? latestArrival : eveningLimit(parts.getFirst(), departure);
        if (!limit.isAfter(departure)) {
            throw new InvalidSuggestionInputException("latest arrival must be after the departure " + departure);
        }
        List<Long> segmentIds = parts.stream().map(SectionPart::segmentId).toList();
        // the restrictions are the incidents themselves here, as in Scenario.evaluationConstraints
        CandidateEvaluator.Outcome outcome = CandidateEvaluator.evaluate(steps, departure, RouteOptions.none(),
                activeIncidents.onSegments(new HashSet<>(segmentIds), departure));
        List<CandidateEvaluator.Passage> passages = outcome.passages();
        Integer obstacle = IntStream.range(0, passages.size())
                .filter(i -> passages.get(i).blocked() || obstacleSegmentIds.contains(passages.get(i).segmentId()))
                .boxed().findFirst().orElse(null);
        if (obstacle == null && !outcome.arrival().isAfter(limit)) {
            return OvernightAreas.problem(StopoverProblem.ARRIVES_BEFORE_LIMIT);
        }
        RecommendationProperties.Stopover rules = properties.stopover();
        List<OvernightArea> areas = OvernightAreaFinder.select(outcome, parts,
                places.near(segmentIds, rules.maxOffRouteMeters()), obstacle, limit, wanted, rules.minSpacingMeters());
        return areas.isEmpty() ? OvernightAreas.problem(StopoverProblem.NOTHING_REACHABLE)
                : new OvernightAreas(areas, null);
    }

    /** First evening limit after the departure, in the local time of the region. */
    private OffsetDateTime eveningLimit(SectionPart first, OffsetDateTime departure) {
        ZonedDateTime local = departure.atZoneSameInstant(segmentQueries.zone(first.segmentId()));
        ZonedDateTime limit = local.with(properties.stopover().latestArrival());
        return (limit.isAfter(local) ? limit : limit.plusDays(1)).toOffsetDateTime();
    }

    private int wanted(@Nullable Integer maxResults) {
        RecommendationProperties.Stopover rules = properties.stopover();
        int wanted = maxResults == null ? rules.defaultAreas() : maxResults;
        if (wanted < 1 || wanted > rules.maxAreas()) {
            throw new InvalidSuggestionInputException("max_results must be between 1 and " + rules.maxAreas());
        }
        return wanted;
    }
}
