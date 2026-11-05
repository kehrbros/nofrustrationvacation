package com.nfv.recommendations.logic;

import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.logic.IncidentLogic;
import com.nfv.network.logic.RegionImport;
import com.nfv.network.logic.RegionStatus;
import com.nfv.trips.data.RouteRepository;
import com.nfv.trips.data.RouteSectionRepository;
import com.nfv.trips.data.StopRepository;
import com.nfv.trips.data.TripRepository;
import com.nfv.trips.data.WaypointRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Suggestions when planning on central Wellington, see Docs/recommendations.md#tests. */
@SpringBootTest
class SuggestionsTest {
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
    // Newtown to Thorndon (5 km) has several alternatives, Kelburn is a detour off all of them
    private static final double[] NEWTOWN = {-41.3100, 174.7790};
    private static final double[] THORNDON = {-41.2755, 174.7790};
    private static final double[] KELBURN = {-41.2870, 174.7680};

    @Autowired
    private Suggestions suggestions;
    @Autowired
    private RoutePlanning routePlanning;
    @Autowired
    private RegionImport regionImport;
    @Autowired
    private IncidentLogic incidentLogic;
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

    private long userId;

    @BeforeEach
    void wellington() {
        if (regionImport.status("wellington").state() != RegionStatus.State.READY) {
            regionImport.importRegion("wellington");
        }
        userId = inTransaction(() -> appUserProvisioning.findOrCreate("suggestions-" + UUID.randomUUID(), null,
                null).getId());
    }

    @Test
    void planningIgnoresWaypointsByDefault() {
        long routeId = newRoute(DEPARTURE);
        long kelburn = addWaypoint(routeId);
        routePlanning.plan(routeId);

        SuggestionResult result = suggestions.forPlanning(userId, routeId, null, false, null);

        assertThat(result.problem()).isNull();
        assertThat(result.waypointMode()).isEqualTo(WaypointMode.IGNORE);
        List<SuggestedOption> options = result.options();
        assertThat(options).hasSizeBetween(2, 3);
        assertThat(options).extracting(SuggestedOption::rank).containsExactly(rankList(options.size()));
        assertThat(options.getFirst().labels()).contains(OptionLabel.FASTEST);
        assertThat(options).flatExtracting(SuggestedOption::points).extracting(SuggestedOption.Point::waypointId)
                .doesNotContain(kelburn);
        assertThat(options).isSortedAccordingTo(Comparator.comparing(SuggestedOption::arrivalAt));
        assertThat(result.reference()).isNotNull();
        assertThat(options.getFirst().arrivalAt()).as("faster than the planned detour via Kelburn")
                .isBefore(result.reference().arrivalAt());
    }

    @Test
    void planningFollowsWaypointsWithFlag() {
        long routeId = newRoute(DEPARTURE);
        long kelburn = addWaypoint(routeId);
        routePlanning.plan(routeId);

        SuggestionResult result = suggestions.forPlanning(userId, routeId, 3, true, null);

        assertThat(result.waypointMode()).isEqualTo(WaypointMode.FOLLOW);
        assertThat(result.options()).isNotEmpty().allSatisfy(option -> assertThat(option.points())
                .extracting(SuggestedOption.Point::waypointId).filteredOn(Objects::nonNull).containsExactly(kelburn));
        assertThat(result.options().getFirst().labels()).contains(OptionLabel.CURRENT);
    }

    @Test
    void suggestionsAreComputedAgainAndNotStored() {
        long routeId = newRoute(DEPARTURE);
        routePlanning.plan(routeId);

        SuggestionResult first = suggestions.forPlanning(userId, routeId, 3, false, null);
        SuggestionResult second = suggestions.forPlanning(userId, routeId, 3, false, null);

        assertThat(second.options()).extracting(SuggestedOption::geometry)
                .isEqualTo(first.options().stream().map(SuggestedOption::geometry).toList());
        assertThat(first.departureAt()).isEqualTo(DEPARTURE);
    }

    @Test
    void closureIsEvaluatedNotAvoidedByRouting() {
        long routeId = newRoute(DEPARTURE);
        routePlanning.plan(routeId);
        long closed = middleSegment(routeId);
        inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "closed", DEPARTURE.minusHours(1),
                DEPARTURE.plusHours(3), null, Set.of(closed)));

        SuggestionResult result = suggestions.forPlanning(userId, routeId, 3, false, null);

        assertThat(result.reference().blocked()).isTrue();
        assertThat(result.options()).isNotEmpty().allSatisfy(option -> {
            assertThat(option.conflicts()).isEmpty();
            assertThat(option.labels()).contains(OptionLabel.AVOIDS_INCIDENT);
        });
    }

    @Test
    void missingDepartureIsAProblem() {
        long routeId = newRoute(null);

        assertThat(suggestions.forPlanning(userId, routeId, 3, false, null).problem())
                .isEqualTo(SuggestionProblem.MISSING_DEPARTURE);
        assertThat(suggestions.forPlanning(userId, routeId, 3, false, DEPARTURE).problem()).isNull();
    }

    @Test
    void countOutOfRangeIsRejected() {
        long routeId = newRoute(DEPARTURE);

        assertThatThrownBy(() -> suggestions.forPlanning(userId, routeId, 6, false, null))
                .isInstanceOf(InvalidSuggestionInputException.class);
    }

    @Test
    void routeOfAnotherUserIsNotFound() {
        long routeId = newRoute(DEPARTURE);
        long other = inTransaction(() -> appUserProvisioning.findOrCreate("other-" + UUID.randomUUID(), null, null)
                .getId());

        assertThatThrownBy(() -> suggestions.forPlanning(other, routeId, 3, false, null))
                .isInstanceOf(TripItemNotFoundException.class);
    }

    private long newRoute(@Nullable OffsetDateTime departure) {
        return inTransaction(() -> {
            Trip trip = tripRepository.save(new Trip(userId, "Wellington", DEPARTURE, DEPARTURE.plusDays(7),
                    DEPARTURE.minusDays(2)));
            Stop start = stopRepository.save(new Stop(trip, 0, "Newtown", NEWTOWN[0], NEWTOWN[1], null, departure));
            Stop end = stopRepository.save(new Stop(trip, 1, "Thorndon", THORNDON[0], THORNDON[1], null, null));
            return routeRepository.save(new Route(trip, start, end)).getId();
        });
    }

    private long addWaypoint(long routeId) {
        return inTransaction(() -> waypointRepository.save(new Waypoint(routeRepository.findById(routeId)
                .orElseThrow(), 0, KELBURN[0], KELBURN[1], "Kelburn", null, WaypointOrigin.USER)).getId());
    }

    /** Longest segment in the middle third of the planned route, away from the stops. */
    private long middleSegment(long routeId) {
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        return sections.subList(sections.size() / 3, 2 * sections.size() / 3).stream()
                .max(Comparator.comparing(s -> java.time.Duration.between(s.getExpectedPassageStart(),
                        s.getExpectedPassageEnd())))
                .orElseThrow().getSegmentId();
    }

    private static Integer[] rankList(int size) {
        Integer[] ranks = new Integer[size];
        for (int i = 0; i < size; i++) {
            ranks[i] = i + 1;
        }
        return ranks;
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(_ -> action.get());
    }
}