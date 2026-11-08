package com.nfv.recommendations.logic;

import com.nfv.incidents.data.ConsequenceRepository;
import com.nfv.incidents.entities.Consequence;
import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.entities.MatchKind;
import com.nfv.incidents.entities.VehicleScope;
import com.nfv.incidents.logic.IncidentDetails;
import com.nfv.incidents.logic.IncidentLogic;
import com.nfv.incidents.logic.TimeWindow;
import com.nfv.network.logic.RegionImport;
import com.nfv.network.logic.RegionStatus;
import com.nfv.trips.data.RouteRepository;
import com.nfv.trips.data.RouteSectionRepository;
import com.nfv.trips.data.StopRepository;
import com.nfv.trips.data.TripRepository;
import com.nfv.trips.data.WaypointRepository;
import com.nfv.trips.entities.PlanOutcome;
import com.nfv.trips.entities.Route;
import com.nfv.trips.entities.RouteSection;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.entities.Trip;
import com.nfv.trips.entities.Waypoint;
import com.nfv.trips.entities.WaypointOrigin;
import com.nfv.trips.logic.RoutePlanning;
import com.nfv.trips.logic.TripItemNotFoundException;
import com.nfv.user.logic.AppUserProvisioning;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Suggestions for incidents on central Wellington, see Docs/recommendations.md#tests. */
@SpringBootTest
class SuggestionsForConsequenceTest {
    /** Incidents stay in the database of the test run and tests use the same roads: each test gets its own days. */
    private static final AtomicInteger TESTS = new AtomicInteger();
    private static final Set<OptionKind> ROUTES = Set.of(OptionKind.ALTERNATIVES);
    private static final Set<OptionKind> SHIFTS = Set.of(OptionKind.DEPARTURE_SHIFT);
    private static final double[] NEWTOWN = {-41.3100, 174.7790};
    private static final double[] THORNDON = {-41.2755, 174.7790};
    private static final double[] KELBURN = {-41.2870, 174.7680};

    @Autowired
    private Suggestions suggestions;
    @Autowired
    private RouteChoice routeChoice;
    @Autowired
    private RoutePlanning routePlanning;
    @Autowired
    private RegionImport regionImport;
    @Autowired
    private JdbcTemplate jdbcTemplate;
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
    private WaypointRepository waypointRepository;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private final OffsetDateTime planned = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30L + 15L * TESTS.incrementAndGet())
            .withNano(0);
    private long userId;

    @BeforeEach
    void wellington() {
        if (regionImport.status("wellington").state() != RegionStatus.State.READY) {
            regionImport.loadOrImport("wellington");
        }
        userId = inTransaction(() -> appUserProvisioning.findOrCreate("consequence-" + UUID.randomUUID(), null,
                null).getId());
    }

    @Test
    void closureIsAvoidedAndChoosingKeepsTheRouteAwayFromIt() {
        long routeId = plannedRoute(planned, null);
        long closed = middleSegment(routeId);
        closure(closed, planned.minusHours(1), planned.plusHours(3));
        long consequenceId = consequence(routeId);

        SuggestionResult result = suggestions.forConsequence(userId, consequenceId, ROUTES, 3, null);

        assertThat(result.problem()).isNull();
        assertThat(result.waypointMode()).isEqualTo(WaypointMode.FOLLOW);
        assertThat(result.reference().blocked()).isTrue();
        assertThat(result.options()).isNotEmpty().allSatisfy(option -> {
            assertThat(option.kind()).isEqualTo(OptionKind.ALTERNATIVES);
            assertThat(option.conflicts()).isEmpty();
            assertThat(option.labels()).contains(OptionLabel.AVOIDS_INCIDENT);
        });

        // the plan after choosing does not know the closure: the points alone have to lead around it
        RouteChoice.Chosen chosen = routeChoice.choose(userId, routeId, result.options().getFirst().points());

        assertThat(chosen.result().outcome()).isEqualTo(PlanOutcome.PLANNED);
        assertThat(routeSectionRepository.findByRouteIdOrderByPosition(routeId))
                .extracting(RouteSection::getSegmentId).doesNotContain(closed);
        assertThat(consequencesOf(routeId)).isEmpty();
    }

    @Test
    void delayCountsOnce() {
        long routeId = plannedRoute(planned, null);
        inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "slow", planned.minusHours(1),
                planned.plusHours(3), Duration.ofMinutes(30), Set.of(middleSegment(routeId))));

        SuggestionResult result = suggestions.forConsequence(userId, consequence(routeId), ROUTES, 3, null);

        assertThat(result.reference().delay()).isEqualTo(Duration.ofMinutes(30));
        assertThat(result.options()).isNotEmpty()
                .allSatisfy(option -> assertThat(option.delay()).isIn(Duration.ZERO, Duration.ofMinutes(30)));
    }

    @Test
    void waypointsStayAndOnlyTheAffectedLegChanges() {
        long routeId = plannedRoute(planned, KELBURN);
        long kelburn = waypointRepository.findByRouteIdOrderByPosition(routeId).getFirst().getId();
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        // last part of the route: behind the waypoint
        long closed = sections.get(sections.size() - sections.size() / 6).getSegmentId();
        closure(closed, planned.minusHours(1), planned.plusHours(3));

        SuggestionResult result = suggestions.forConsequence(userId, consequence(routeId), ROUTES, 3, null);

        assertThat(result.options()).isNotEmpty().allSatisfy(option -> {
            assertThat(option.points()).extracting(SuggestedOption.Point::waypointId).filteredOn(Objects::nonNull)
                    .containsExactly(kelburn);
            assertThat(option.geometry().subList(0, 10)).as("first leg as planned")
                    .isEqualTo(result.options().getFirst().geometry().subList(0, 10));
            assertThat(option.conflicts()).isEmpty();
        });
    }

    @Test
    void closureAtAWaypointMakesItUnreachable() {
        long routeId = plannedRoute(planned, KELBURN);
        // all roads around the waypoint
        Set<Long> closed = Set.copyOf(jdbcTemplate.queryForList("SELECT id FROM road_segment WHERE "
                        + "ST_DWithin(geometry::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, 200)",
                Long.class, KELBURN[1], KELBURN[0]));
        inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "closed", planned.minusHours(1),
                planned.plusHours(3), null, closed));

        SuggestionResult result = suggestions.forConsequence(userId, consequence(routeId), ROUTES, 3, null);

        assertThat(result.options()).isEmpty();
        assertThat(result.problem()).isEqualTo(SuggestionProblem.WAYPOINT_UNREACHABLE);
        assertThat(result.reference()).as("planned route stays as comparison").isNotNull();
    }

    @Test
    void departureMovesPastAndBeforeAWindowOfAClosure() {
        long routeId = plannedRoute(planned, null);
        long closed = middleSegment(routeId);
        windowClosure(closed, planned.minusMinutes(30), planned.plusHours(3));

        SuggestionResult result = suggestions.forConsequence(userId, consequence(routeId), SHIFTS, 3, null);

        assertThat(result.problem()).isNull();
        assertThat(result.reference().blocked()).isTrue();
        assertThat(result.options()).hasSize(2).allSatisfy(option -> {
            assertThat(option.kind()).isEqualTo(OptionKind.DEPARTURE_SHIFT);
            assertThat(option.conflicts()).isEmpty();
            assertThat(option.points()).isEmpty();
        });
        assertThat(result.options()).extracting(SuggestedOption::labels)
                .anyMatch(labels -> labels.contains(OptionLabel.LATER_DEPARTURE))
                .anyMatch(labels -> labels.contains(OptionLabel.EARLIER_DEPARTURE));
        SuggestedOption later = result.options().stream()
                .filter(option -> option.labels().contains(OptionLabel.LATER_DEPARTURE)).findFirst().orElseThrow();
        assertThat(later.departureAt()).isAfter(planned);

        RouteChoice.Chosen chosen = routeChoice.chooseDeparture(userId, routeId, later.departureAt());

        assertThat(chosen.result().outcome()).isEqualTo(PlanOutcome.PLANNED);
        // the buffer equals the tolerance of the assessment: "just passes" at most, never too late
        assertThat(consequencesOf(routeId)).allSatisfy(c -> assertThat(c.getMatch()).isEqualTo(MatchKind.MARGINAL));
    }

    @Test
    void closureWithoutEndHasNoLaterDeparture() {
        long routeId = plannedRoute(planned, null);
        long closed = middleSegment(routeId);
        long incident = inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "closed",
                planned.minusMinutes(30), null, null, Set.of(closed)).getId());

        SuggestionResult result = suggestions.forConsequence(userId, consequence(routeId), SHIFTS, 3, null);

        assertThat(result.options()).extracting(SuggestedOption::labels)
                .noneMatch(labels -> labels.contains(OptionLabel.LATER_DEPARTURE));
        // without an end it would block this road for all later tests
        inTransaction(() -> incidentLogic.change(incident, "closed", planned.minusMinutes(30), planned, null,
                Set.of(closed)));
    }

    @Test
    void underwaySuggestionsStartAtTheEstimatedPosition() {
        OffsetDateTime departed = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(3).withNano(0);
        long routeId = plannedRoute(departed, null);
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        double fullMeters = suggestions.forPlanning(userId, routeId, 1, false, null).reference().distanceMeters();
        closure(sections.get(sections.size() - sections.size() / 4).getSegmentId(), departed.minusHours(1),
                departed.plusHours(3));

        SuggestionResult result = suggestions.forConsequence(userId, consequence(routeId),
                Set.of(OptionKind.ALTERNATIVES, OptionKind.DEPARTURE_SHIFT), 3, null);

        assertThat(result.departureAt()).isAfter(departed);
        assertThat(result.reference().distanceMeters()).isLessThan(fullMeters);
        assertThat(result.options()).isNotEmpty()
                .allSatisfy(option -> assertThat(option.kind()).isEqualTo(OptionKind.ALTERNATIVES));
    }

    @Test
    void passedIncidentHasNoSuggestions() {
        OffsetDateTime departed = OffsetDateTime.now(ZoneOffset.UTC).minusHours(2).withNano(0);
        long routeId = plannedRoute(departed, null);
        long segment = routeSectionRepository.findByRouteIdOrderByPosition(routeId).get(2).getSegmentId();
        inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "slow", departed.minusHours(1),
                departed.plusHours(5), Duration.ofMinutes(10), Set.of(segment)));

        assertThat(suggestions.forConsequence(userId, consequence(routeId), ROUTES, 3, null).problem())
                .isEqualTo(SuggestionProblem.CONSEQUENCE_PASSED);
    }

    @Test
    void heavyOnlyClosureDoesNotBlockCars() {
        long routeId = plannedRoute(planned, null);
        long heavy = middleSegment(routeId);
        inTransaction(() -> incidentLogic.create(new IncidentDetails(IncidentType.CLOSURE, "trucks only",
                planned.minusHours(1), planned.plusHours(3), null, null, List.of(), VehicleScope.HEAVY_ONLY, false),
                Set.of(heavy)));

        SuggestionResult result = suggestions.forConsequence(userId, consequence(routeId), SHIFTS, 3, null);

        assertThat(result.reference().blocked()).as("does not affect cars").isFalse();
    }

    @Test
    void invalidInputIsRejected() {
        long routeId = plannedRoute(planned, null);
        closure(middleSegment(routeId), planned.minusHours(1), planned.plusHours(3));
        long consequenceId = consequence(routeId);

        assertThatThrownBy(() -> suggestions.forConsequence(userId, consequenceId, Set.of(), 3, null))
                .isInstanceOf(InvalidSuggestionInputException.class);
        assertThatThrownBy(() -> suggestions.forConsequence(userId, consequenceId, ROUTES, 6, null))
                .isInstanceOf(InvalidSuggestionInputException.class);
        assertThatThrownBy(() -> suggestions.forConsequence(userId, consequenceId, ROUTES, 3,
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)))
                .isInstanceOf(InvalidSuggestionInputException.class);
        assertThatThrownBy(() -> routeChoice.chooseDeparture(userId, routeId,
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(1)))
                .isInstanceOf(InvalidSuggestionInputException.class);
    }

    @Test
    void consequenceOfAnotherUserIsNotFound() {
        long routeId = plannedRoute(planned, null);
        closure(middleSegment(routeId), planned.minusHours(1), planned.plusHours(3));
        long consequenceId = consequence(routeId);
        long other = inTransaction(() -> appUserProvisioning.findOrCreate("other-" + UUID.randomUUID(), null, null)
                .getId());

        assertThatThrownBy(() -> suggestions.forConsequence(other, consequenceId, ROUTES, 3, null))
                .isInstanceOf(TripItemNotFoundException.class);
        assertThatThrownBy(() -> suggestions.forConsequence(userId, -1, ROUTES, 3, null))
                .isInstanceOf(TripItemNotFoundException.class);
    }

    private long plannedRoute(OffsetDateTime departure, @Nullable double[] waypoint) {
        long routeId = inTransaction(() -> {
            Trip trip = tripRepository.save(new Trip(userId, "Wellington", departure.minusDays(1), departure.plusDays(7),
                    departure.minusDays(2)));
            Stop start = stopRepository.save(new Stop(trip, 0, "Newtown", NEWTOWN[0], NEWTOWN[1], null, departure));
            Stop end = stopRepository.save(new Stop(trip, 1, "Thorndon", THORNDON[0], THORNDON[1], null, null));
            Route route = routeRepository.save(new Route(trip, start, end));
            if (waypoint != null) {
                waypointRepository.save(new Waypoint(route, 0, waypoint[0], waypoint[1], "Kelburn", null,
                        WaypointOrigin.USER));
            }
            return route.getId();
        });
        routePlanning.plan(routeId);
        return routeId;
    }

    private void closure(long segmentId, OffsetDateTime from, @Nullable OffsetDateTime to) {
        inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "closed", from, to, null, Set.of(segmentId)));
    }

    /** Valid for a week, closed only within the window. */
    private void windowClosure(long segmentId, OffsetDateTime from, OffsetDateTime to) {
        inTransaction(() -> incidentLogic.create(new IncidentDetails(IncidentType.CLOSURE, "closed",
                from.minusDays(1), from.plusDays(7), null, null, List.of(new TimeWindow(from, to)), VehicleScope.ALL,
                false), Set.of(segmentId)));
    }

    private long consequence(long routeId) {
        return consequencesOf(routeId).getFirst().getId();
    }

    private List<Consequence> consequencesOf(long routeId) {
        return inTransaction(() -> consequenceRepository.findByRouteId(routeId));
    }

    /** Longest segment in the middle third of the planned route, away from the stops. */
    private long middleSegment(long routeId) {
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        return sections.subList(sections.size() / 3, 2 * sections.size() / 3).stream()
                .max(Comparator.comparing(s -> Duration.between(s.getExpectedPassageStart(), s.getExpectedPassageEnd())))
                .orElseThrow().getSegmentId();
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(_ -> action.get());
    }
}
