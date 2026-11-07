package com.nfv.recommendations.logic;

import com.nfv.incidents.data.ConsequenceRepository;
import com.nfv.incidents.entities.Consequence;
import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.logic.IncidentLogic;
import com.nfv.network.logic.RegionImport;
import com.nfv.network.logic.RegionStatus;
import com.nfv.network.logic.RoutedSegment;
import com.nfv.recommendations.logic.Stopovers.OvernightArea;
import com.nfv.recommendations.logic.Stopovers.OvernightAreas;
import com.nfv.recommendations.logic.Stopovers.StopoverProblem;
import com.nfv.trips.data.RouteRepository;
import com.nfv.trips.data.RouteSectionRepository;
import com.nfv.trips.data.StopRepository;
import com.nfv.trips.data.TripRepository;
import com.nfv.trips.entities.Route;
import com.nfv.trips.entities.RouteSection;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.entities.Trip;
import com.nfv.trips.logic.RoutePlanning;
import com.nfv.trips.logic.TripItemNotFoundException;
import com.nfv.user.logic.AppUserProvisioning;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Places along routes on central Wellington, see Docs/advisor.md#find_overnight_areas. Distances are small there, so
 * the limits are too.
 */
@SpringBootTest(properties = {
        "nfv.recommendations.stopover.min-spacing-meters=100",
        "nfv.recommendations.stopover.max-off-route-meters=150"})
class StopoversTest {
    /** Incidents stay in the database of the test run and tests use the same roads: each test gets its own days. */
    private static final AtomicInteger TESTS = new AtomicInteger();
    private static final ZoneId NZ = ZoneId.of("Pacific/Auckland");
    private static final String REGION = "wellington";
    private static final double[] NEWTOWN = {-41.3100, 174.7790};
    private static final double[] THORNDON = {-41.2755, 174.7790};

    @Autowired
    private Stopovers stopovers;
    @Autowired
    private Suggestions suggestions;
    @Autowired
    private RoutePlanning routePlanning;
    @Autowired
    private RegionImport regionImport;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private IncidentLogic incidentLogic;
    @Autowired
    private ConsequenceRepository consequenceRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private StopRepository stopRepository;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private RouteSectionRepository routeSectionRepository;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private final OffsetDateTime planned = OffsetDateTime.now(ZoneOffset.UTC)
            .plusDays(800L + 15L * TESTS.incrementAndGet()).withNano(0);
    private long userId;
    private long versionId;

    @BeforeEach
    void wellington() {
        if (regionImport.status(REGION).state() != RegionStatus.State.READY) {
            regionImport.importRegion(REGION);
        }
        versionId = regionImport.status(REGION).extractVersionId();
        userId = inTransaction(() -> appUserProvisioning.findOrCreate("stopover-" + UUID.randomUUID(), null, null)
                .getId());
    }

    @AfterEach
    void removePlaces() {
        jdbc.update("DELETE FROM place WHERE region = ?", REGION);
    }

    @Test
    void placeJustPastTheClosureIsNotOffered() {
        long routeId = plannedRoute(planned);
        List<RouteSection> sections = sections(routeId);
        int closed = sections.size() / 2;
        closure(sections.get(closed).getSegmentId(), planned);
        place("Before", sections.get(closed / 2).getSegmentId());
        place("Past", sections.get(closed + 2).getSegmentId());

        OvernightAreas result = stopovers.onPlannedRoute(userId, lastConsequence(routeId), planned.plusHours(6), 5);

        assertThat(result.problem()).isNull();
        assertThat(result.areas()).extracting(OvernightArea::name).containsExactly("Before");
        OvernightArea area = result.areas().getFirst();
        assertThat(area.arrivalAt()).isBetween(planned, sections.get(closed).getExpectedPassageStart());
        assertThat(area.remainingMeters()).isPositive();
        assertThat(area.offRouteMeters()).isLessThan(1);
    }

    @Test
    void earlierClosureEndsTheSearchEvenIfTheRunIsAboutALaterOne() {
        long routeId = plannedRoute(planned);
        List<RouteSection> sections = sections(routeId);
        int first = sections.size() / 3;
        int second = 2 * sections.size() / 3;
        closure(sections.get(first).getSegmentId(), planned);
        closure(sections.get(second).getSegmentId(), planned);
        place("Reachable", sections.get(first / 2).getSegmentId());
        place("Between", sections.get((first + second) / 2).getSegmentId());

        OvernightAreas result = stopovers.onPlannedRoute(userId, lastConsequence(routeId), planned.plusHours(6), 5);

        assertThat(result.areas()).extracting(OvernightArea::name).containsExactly("Reachable");
    }

    @Test
    void delaysBeforeThePlaceMoveItsArrival() {
        long routeId = plannedRoute(planned);
        List<RouteSection> sections = sections(routeId);
        int delayed = sections.size() / 5;
        inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "slow", planned.minusHours(1),
                planned.plusHours(3), Duration.ofMinutes(30), Set.of(sections.get(delayed).getSegmentId())));
        closure(sections.get(4 * sections.size() / 5).getSegmentId(), planned);
        place("Behind the delay", sections.get(2 * sections.size() / 5).getSegmentId());

        OvernightAreas result = stopovers.onPlannedRoute(userId, lastConsequence(routeId), planned.plusHours(6), 5);

        assertThat(result.areas()).singleElement()
                .satisfies(area -> assertThat(area.arrivalAt()).isAfter(planned.plusMinutes(30)));
    }

    @Test
    void withoutLimitTheEveningOfTheRegionCounts() {
        OffsetDateTime beforeTen = planned.atZoneSameInstant(NZ).with(LocalTime.of(21, 58)).toOffsetDateTime();
        long routeId = plannedRoute(beforeTen);
        List<RouteSection> sections = sections(routeId);
        closure(sections.get(sections.size() - 2).getSegmentId(), beforeTen);
        place("Early", sections.get(1).getSegmentId());
        place("After ten", sections.get(sections.size() - 3).getSegmentId());

        OvernightAreas result = stopovers.onPlannedRoute(userId, lastConsequence(routeId), null, 5);

        assertThat(result.areas()).extracting(OvernightArea::name).containsExactly("Early");
    }

    @Test
    void departureAfterTheEveningLimitLooksAtTheNextEvening() {
        OffsetDateTime afterTen = planned.atZoneSameInstant(NZ).with(LocalTime.of(23, 0)).toOffsetDateTime();
        long routeId = plannedRoute(afterTen);
        List<RouteSection> sections = sections(routeId);
        closure(sections.get(sections.size() / 2).getSegmentId(), afterTen);
        place("Reachable", sections.get(sections.size() / 4).getSegmentId());

        OvernightAreas result = stopovers.onPlannedRoute(userId, lastConsequence(routeId), null, 5);

        assertThat(result.areas()).extracting(OvernightArea::name).containsExactly("Reachable");
    }

    @Test
    void underwayArrivalsStartFromNow() {
        OffsetDateTime departed = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1).withNano(0);
        long routeId = plannedRoute(departed);
        List<RouteSection> sections = sections(routeId);
        int closed = sections.size() - sections.size() / 4;
        closure(sections.get(closed).getSegmentId(), departed);
        place("Ahead", sections.get(closed - 2).getSegmentId());
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC);

        OvernightAreas result = stopovers.onPlannedRoute(userId, lastConsequence(routeId), departed.plusHours(6), 5);

        assertThat(result.areas()).singleElement()
                .satisfies(area -> assertThat(area.arrivalAt()).isAfterOrEqualTo(before));
    }

    @Test
    void alternativeHasItsOwnPlacesAndLimit() {
        long routeId = plannedRoute(planned);
        List<RouteSection> sections = sections(routeId);
        closure(sections.get(sections.size() / 2).getSegmentId(), planned);
        long consequenceId = lastConsequence(routeId);
        SuggestedOption option = suggestions.forConsequence(userId, consequenceId, Set.of(OptionKind.ALTERNATIVES), 1,
                null).options().getFirst();
        List<RoutedSegment> path = option.path();
        assertThat(path).isNotEmpty();
        place("Early", path.get(1).segmentId());
        place("Late", path.get(path.size() - 2).segmentId());

        OvernightAreas inTime = stopovers.onOption(userId, consequenceId, path, option.departureAt(),
                option.departureAt().plus(option.duration().dividedBy(4)), 5);
        OvernightAreas relaxed = stopovers.onOption(userId, consequenceId, path, option.departureAt(),
                option.arrivalAt().plusHours(1), 5);

        assertThat(inTime.areas()).extracting(OvernightArea::name).containsExactly("Early");
        assertThat(relaxed.problem()).isEqualTo(StopoverProblem.ARRIVES_BEFORE_LIMIT);
    }

    @Test
    void closureOnTheAlternativeEndsTheSearchThere() {
        long routeId = plannedRoute(planned);
        List<RouteSection> sections = sections(routeId);
        closure(sections.get(sections.size() / 2).getSegmentId(), planned);
        long consequenceId = lastConsequence(routeId);
        SuggestedOption option = suggestions.forConsequence(userId, consequenceId, Set.of(OptionKind.ALTERNATIVES), 1,
                null).options().getFirst();
        List<RoutedSegment> path = option.path();
        closure(path.get(3 * path.size() / 4).segmentId(), planned);
        place("Before", path.get(path.size() / 4).segmentId());
        place("Past", path.get(7 * path.size() / 8).segmentId());

        OvernightAreas result = stopovers.onOption(userId, consequenceId, path, option.departureAt(),
                planned.plusHours(6), 5);

        assertThat(result.areas()).extracting(OvernightArea::name).containsExactly("Before");
    }

    @Test
    void noPlaceMeansNothingReachable() {
        long routeId = plannedRoute(planned);
        closure(sections(routeId).get(sections(routeId).size() / 2).getSegmentId(), planned);

        OvernightAreas result = stopovers.onPlannedRoute(userId, lastConsequence(routeId), planned.plusHours(6), 5);

        assertThat(result.problem()).isEqualTo(StopoverProblem.NOTHING_REACHABLE);
        assertThat(result.areas()).isEmpty();
    }

    @Test
    void passedConsequenceIsNotUsable() {
        OffsetDateTime departed = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2).withNano(0);
        long routeId = plannedRoute(departed);
        long segment = sections(routeId).get(2).getSegmentId();
        inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "slow", departed.minusHours(1),
                departed.plusHours(5), Duration.ofMinutes(10), Set.of(segment)));

        OvernightAreas result = stopovers.onPlannedRoute(userId, lastConsequence(routeId), null, 5);

        assertThat(result.problem()).isEqualTo(StopoverProblem.ROUTE_NOT_USABLE);
    }

    @Test
    void wrongInputIsRefused() {
        long routeId = plannedRoute(planned);
        closure(sections(routeId).get(sections(routeId).size() / 2).getSegmentId(), planned);
        long consequenceId = lastConsequence(routeId);

        assertThatThrownBy(() -> stopovers.onPlannedRoute(userId, consequenceId, planned.minusMinutes(1), 5))
                .isInstanceOf(InvalidSuggestionInputException.class);
        assertThatThrownBy(() -> stopovers.onPlannedRoute(userId, consequenceId, null, 6))
                .isInstanceOf(InvalidSuggestionInputException.class);
        assertThatThrownBy(() -> stopovers.onPlannedRoute(userId, consequenceId, null, 0))
                .isInstanceOf(InvalidSuggestionInputException.class);
        assertThatThrownBy(() -> stopovers.onPlannedRoute(userId + 1_000_000, consequenceId, null, 5))
                .isInstanceOf(TripItemNotFoundException.class);
        assertThatThrownBy(() -> stopovers.onPlannedRoute(userId, -1, null, 5))
                .isInstanceOf(TripItemNotFoundException.class);
    }

    private void place(String name, long segmentId) {
        jdbc.update("""
                INSERT INTO place (region, extract_version_id, name, kind, geometry)
                SELECT ?, ?, ?, 'TOWN', ST_LineInterpolatePoint(geometry, 0.5) FROM road_segment WHERE id = ?""",
                REGION, versionId, name, segmentId);
    }

    private long plannedRoute(OffsetDateTime departure) {
        long routeId = inTransaction(() -> {
            Trip trip = tripRepository.save(new Trip(userId, "Wellington", departure.minusDays(1), departure.plusDays(7),
                    departure.minusDays(2)));
            Stop start = stopRepository.save(new Stop(trip, 0, "Newtown", NEWTOWN[0], NEWTOWN[1], null, departure));
            Stop end = stopRepository.save(new Stop(trip, 1, "Thorndon", THORNDON[0], THORNDON[1], null, null));
            return routeRepository.save(new Route(trip, start, end)).getId();
        });
        routePlanning.plan(routeId);
        return routeId;
    }

    private List<RouteSection> sections(long routeId) {
        return routeSectionRepository.findByRouteIdOrderByPosition(routeId);
    }

    private void closure(long segmentId, OffsetDateTime around) {
        inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "closed", around.minusHours(1),
                around.plusHours(3), null, Set.of(segmentId)));
    }

    /** The consequence furthest along the route. */
    private long lastConsequence(long routeId) {
        return inTransaction(() -> consequenceRepository.findByRouteId(routeId)).stream()
                .max(Comparator.comparingLong(Consequence::getRouteSectionId)).orElseThrow().getId();
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(_ -> action.get());
    }
}
