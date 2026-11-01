package com.vacantagent.trips;

import com.vacantagent.incidents.data.ConsequenceRepository;
import com.vacantagent.incidents.entities.Consequence;
import com.vacantagent.incidents.entities.Impact;
import com.vacantagent.incidents.entities.Incident;
import com.vacantagent.incidents.entities.IncidentType;
import com.vacantagent.incidents.logic.IncidentLogic;
import com.vacantagent.network.logic.RegionImport;
import com.vacantagent.network.logic.RegionStatus;
import com.vacantagent.trips.data.RouteRepository;
import com.vacantagent.trips.data.RouteSectionRepository;
import com.vacantagent.trips.data.StopRepository;
import com.vacantagent.trips.data.TripRepository;
import com.vacantagent.trips.data.WaypointRepository;
import com.vacantagent.trips.entities.PlanOutcome;
import com.vacantagent.trips.entities.Route;
import com.vacantagent.trips.entities.RouteSection;
import com.vacantagent.trips.entities.Stop;
import com.vacantagent.trips.entities.Trip;
import com.vacantagent.trips.entities.Waypoint;
import com.vacantagent.trips.logic.NewStop;
import com.vacantagent.trips.logic.NewWaypoint;
import com.vacantagent.trips.logic.PlanResult;
import com.vacantagent.trips.logic.RoutePlanning;
import com.vacantagent.trips.logic.TripEditing;
import com.vacantagent.user.logic.AppUserProvisioning;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** Routes planned via network.Routing on central Wellington, see Docs/domain.md#trips. */
@SpringBootTest
class RoutePlanningTest {
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
    // Te Aro to Thorndon, the urban motorway (SH 1) between them runs through the Terrace Tunnel
    private static final double[] TE_ARO = {-41.2947, 174.7735};
    private static final double[] THORNDON = {-41.2755, 174.7790};
    private static final double[] KELBURN = {-41.2870, 174.7680};

    @Autowired
    private RoutePlanning routePlanning;
    @Autowired
    private TripEditing tripEditing;
    @Autowired
    private RegionImport regionImport;
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
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void wellington() {
        if (regionImport.status("wellington").state() != RegionStatus.State.READY) {
            regionImport.importRegion("wellington");
        }
    }

    @Test
    void routeIsPlannedWithPassageTimesAndFractions() {
        long routeId = newRoute(TE_ARO, THORNDON, DEPARTURE);

        PlanResult result = routePlanning.plan(routeId);

        assertThat(result.outcome()).isEqualTo(PlanOutcome.PLANNED);
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        assertThat(sections).isNotEmpty();
        assertThat(sections.getFirst().getExpectedPassageStart()).isEqualTo(DEPARTURE);
        for (int i = 1; i < sections.size(); i++) {
            assertThat(sections.get(i).getExpectedPassageStart())
                    .isEqualTo(sections.get(i - 1).getExpectedPassageEnd());
        }
        RouteSection first = sections.getFirst();
        RouteSection last = sections.getLast();
        assertThat(first.getFromFraction() > 0 || last.getToFraction() < 1).isTrue();
        assertThat(sections.subList(1, sections.size() - 1)).allSatisfy(section -> {
            assertThat(section.getFromFraction()).isZero();
            assertThat(section.getToFraction()).isEqualTo(1);
        });
        assertThat(routeRepository.findById(routeId).orElseThrow().getPlannedAt()).isNotNull();
    }

    @Test
    void waypointIsPassed() {
        long routeId = newRoute(TE_ARO, THORNDON, DEPARTURE);
        routePlanning.plan(routeId);
        Duration direct = duration(routeId);

        inTransaction(() -> waypointRepository.save(new Waypoint(routeRepository.findById(routeId).orElseThrow(), 0,
                KELBURN[0], KELBURN[1], "Kelburn", null)));
        PlanResult result = routePlanning.plan(routeId);

        assertThat(result.outcome()).isEqualTo(PlanOutcome.PLANNED);
        assertThat(duration(routeId)).isGreaterThan(direct);
    }

    @Test
    void missingDepartureLeavesRouteUnplanned() {
        long routeId = newRoute(TE_ARO, THORNDON, null);

        PlanResult result = routePlanning.plan(routeId);

        assertThat(result.outcome()).isEqualTo(PlanOutcome.MISSING_DEPARTURE);
        assertThat(routeSectionRepository.findByRouteIdOrderByPosition(routeId)).isEmpty();
        Route route = routeRepository.findById(routeId).orElseThrow();
        assertThat(route.getPlanOutcome()).isEqualTo(PlanOutcome.MISSING_DEPARTURE);
        assertThat(route.getPlanReason()).contains("Te Aro");
    }

    /** A new departure discards the sections and plans the route again, from the new departure. */
    @Test
    void changedDepartureReplansTheRoute() {
        long routeId = newRoute(TE_ARO, THORNDON, DEPARTURE);
        routePlanning.plan(routeId);
        Route route = routeRepository.findById(routeId).orElseThrow();
        Stop start = stopRepository.findById(route.getFromStop().getId()).orElseThrow();
        long userId = tripRepository.findById(start.getTrip().getId()).orElseThrow().getAppUserId();

        Map<Long, PlanResult> results = tripEditing.updateStop(userId, start.getTrip().getId(), start.getId(),
                new NewStop("Te Aro", TE_ARO[0], TE_ARO[1], null, DEPARTURE.plusHours(2)));

        assertThat(results).containsOnlyKeys(routeId);
        assertThat(results.get(routeId).outcome()).isEqualTo(PlanOutcome.PLANNED);
        assertThat(routeSectionRepository.findByRouteIdOrderByPosition(routeId).getFirst().getExpectedPassageStart())
                .isEqualTo(DEPARTURE.plusHours(2));
    }

    /** Waypoints are reordered in place (unique positions), changed, added and removed in one call. */
    @Test
    void waypointsAreReplacedAndPlanned() {
        long routeId = newRoute(TE_ARO, THORNDON, DEPARTURE);
        Route route = routeRepository.findById(routeId).orElseThrow();
        long tripId = stopRepository.findById(route.getFromStop().getId()).orElseThrow().getTrip().getId();
        long userId = tripRepository.findById(tripId).orElseThrow().getAppUserId();
        tripEditing.replaceWaypoints(userId, tripId, routeId, List.of(
                new NewWaypoint(null, KELBURN[0], KELBURN[1], "Kelburn", null),
                new NewWaypoint(null, -41.2900, 174.7760, "Lambton", null)));
        List<Waypoint> first = waypointRepository.findByRouteIdOrderByPosition(routeId);

        Map<Long, PlanResult> results = tripEditing.replaceWaypoints(userId, tripId, routeId, List.of(
                new NewWaypoint(first.get(1).getId(), -41.2905, 174.7765, "Lambton Quay", null),
                new NewWaypoint(first.get(0).getId(), KELBURN[0], KELBURN[1], "Kelburn", "Glenmore Street"),
                new NewWaypoint(null, -41.2800, 174.7750, null, null)));

        assertThat(results.get(routeId).outcome()).isEqualTo(PlanOutcome.PLANNED);
        List<Waypoint> second = waypointRepository.findByRouteIdOrderByPosition(routeId);
        assertThat(second).extracting(Waypoint::getId)
                .startsWith(first.get(1).getId(), first.get(0).getId()).hasSize(3);
        assertThat(second).extracting(Waypoint::getPosition).containsExactly(0, 1, 2);
        assertThat(second.get(0).getName()).isEqualTo("Lambton Quay");
        assertThat(second.get(1).getRoadHint()).isEqualTo("Glenmore Street");

        tripEditing.replaceWaypoints(userId, tripId, routeId, List.of());
        assertThat(waypointRepository.findByRouteIdOrderByPosition(routeId)).isEmpty();
    }

    @Test
    void missingCoordinatesLeaveRouteUnplanned() {
        long routeId = newRoute(TE_ARO, null, DEPARTURE);

        PlanResult result = routePlanning.plan(routeId);

        assertThat(result.outcome()).isEqualTo(PlanOutcome.MISSING_COORDINATES);
        assertThat(routeSectionRepository.findByRouteIdOrderByPosition(routeId)).isEmpty();
    }

    /** network → trips → incidents: a closure on a planned segment blocks the route. */
    @Test
    void incidentOnPlannedRouteCreatesConsequence() {
        long routeId = newRoute(TE_ARO, THORNDON, DEPARTURE);
        routePlanning.plan(routeId);
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        RouteSection tunnel = sections.stream().filter(section -> isTunnel(section.getSegmentId())).findFirst()
                .orElseThrow();

        Incident incident = inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "Terrace Tunnel closed",
                DEPARTURE.minusHours(1), DEPARTURE.plusHours(3), null, Set.of(tunnel.getSegmentId())));

        List<Consequence> consequences = consequenceRepository.findByIncidentId(incident.getId()).stream()
                .filter(consequence -> consequence.getRouteId() == routeId).toList();
        assertThat(consequences).singleElement().satisfies(consequence -> {
            assertThat(consequence.getImpact()).isEqualTo(Impact.BLOCKED);
            assertThat(consequence.getRouteSectionId()).isEqualTo(tunnel.getId());
        });
    }

    private long newRoute(double[] from, @Nullable double[] to, @Nullable OffsetDateTime departure) {
        return inTransaction(() -> {
            long userId = appUserProvisioning.findOrCreate("trip-" + UUID.randomUUID(), null, null).getId();
            Trip trip = tripRepository.save(new Trip(userId, "Wellington", DEPARTURE, DEPARTURE.plusDays(7),
                    DEPARTURE.minusDays(2)));
            Stop start = stopRepository.save(new Stop(trip, 0, "Te Aro", from[0], from[1], null, departure));
            Stop end = stopRepository.save(new Stop(trip, 1, "Thorndon", to == null ? null : to[0],
                    to == null ? null : to[1], null, null));
            return routeRepository.save(new Route(trip, start, end)).getId();
        });
    }

    private Duration duration(long routeId) {
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        return Duration.between(sections.getFirst().getExpectedPassageStart(),
                sections.getLast().getExpectedPassageEnd());
    }

    private boolean isTunnel(long segmentId) {
        Boolean tunnel = jdbc.queryForObject("SELECT tunnel AND ref = 'SH 1' FROM road_segment WHERE id = ?",
                Boolean.class, segmentId);
        return Boolean.TRUE.equals(tunnel);
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(_ -> action.get());
    }
}