package com.nfv.advisor;

import com.nfv.advisor.data.AdvisorRunRepository;
import com.nfv.advisor.entities.AdvisorRun;
import com.nfv.advisor.entities.RunStatus;
import com.nfv.advisor.entities.RunTrigger;
import com.nfv.assistant.logic.ToolConversation;
import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.logic.IncidentLogic;
import com.nfv.incidents.logic.RouteTooLate;
import com.nfv.network.logic.RegionImport;
import com.nfv.network.logic.RegionStatus;
import com.nfv.trips.data.RouteRepository;
import com.nfv.trips.data.RouteSectionRepository;
import com.nfv.trips.data.StopRepository;
import com.nfv.trips.data.TripRepository;
import com.nfv.trips.entities.Route;
import com.nfv.trips.entities.RouteSection;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.entities.Trip;
import com.nfv.trips.logic.RoutePlanning;
import com.nfv.user.logic.AppUserProvisioning;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** The whole way with automatic runs switched on: a commit starts a run in the background, a rollback does not. */
@SpringBootTest(properties = "nfv.advisor.auto-enabled=true")
@AutoConfigureMockMvc
@RecordApplicationEvents
class RouteTooLateListenerTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicInteger TESTS = new AtomicInteger();
    private static final double[] NEWTOWN = {-41.3100, 174.7790};
    private static final double[] THORNDON = {-41.2755, 174.7790};

    @MockitoBean
    private ToolConversation conversation;
    @Autowired
    private AdvisorRunRepository runRepository;
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
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ApplicationEvents applicationEvents;

    /** Own days per test, also apart from those of the other tests on these roads. */
    private final OffsetDateTime planned = OffsetDateTime.now(ZoneOffset.UTC)
            .plusDays(2000L + 30L * TESTS.incrementAndGet()).withNano(0);

    @BeforeEach
    void setUp() {
        if (regionImport.status("wellington").state() != RegionStatus.State.READY) {
            regionImport.loadOrImport("wellington");
        }
        when(conversation.runForSystem(any())).thenReturn(JSON.readTree(
                "{\"summary\":\"Stay.\",\"proposals\":[{\"option_id\":\"keep_route\",\"reason\":\"-\"}]}"));
    }

    @Test
    void aCommitStartsARunInTheBackground() throws Exception {
        long routeId = plannedRoute(planned);

        closure(routeId, planned);

        AdvisorRun run = awaitFinished(routeId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getTrigger()).isEqualTo(RunTrigger.AUTO);
    }

    @Test
    void aRollbackStartsNoRun() throws Exception {
        long routeId = plannedRoute(planned);

        transactionTemplate.executeWithoutResult(status -> {
            closure(routeId, planned);
            status.setRollbackOnly();
        });
        assertThat(applicationEvents.stream(RouteTooLate.class)).as("published all the same").isNotEmpty();

        // a run committed after the rollback ends first: had the rollback started one, it would be there as well
        long later = plannedRoute(planned.plusDays(10));
        closure(later, planned.plusDays(10));
        awaitFinished(later);
        Thread.sleep(200);
        assertThat(runs(routeId)).isEmpty();
    }

    private AdvisorRun awaitFinished(long routeId) throws InterruptedException {
        long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < until) {
            List<AdvisorRun> runs = runs(routeId);
            if (!runs.isEmpty() && !runs.getFirst().isUnfinished()) {
                return runs.getFirst();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("no finished run for route " + routeId);
    }

    private List<AdvisorRun> runs(long routeId) {
        return runRepository.findAll().stream().filter(run -> run.getRouteId() == routeId).toList();
    }

    private void closure(long routeId, OffsetDateTime day) {
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        transactionTemplate.execute(_ -> incidentLogic.create(IncidentType.CLOSURE, "closed", day.minusHours(1),
                day.plusHours(3), null, Set.of(sections.get(sections.size() / 2).getSegmentId())));
    }

    /** Arrival time one hour after the departure, the route takes minutes. */
    private long plannedRoute(OffsetDateTime departure) {
        long id = transactionTemplate.execute(_ -> {
            long userId = appUserProvisioning.findOrCreate("listener-" + UUID.randomUUID(), null, null).getId();
            Trip trip = tripRepository.save(new Trip(userId, "Wellington", departure.minusDays(1), departure.plusDays(7),
                    departure.minusDays(2)));
            Stop start = stopRepository.save(new Stop(trip, 0, "Newtown", NEWTOWN[0], NEWTOWN[1], null, departure));
            Stop end = stopRepository.save(new Stop(trip, 1, "Thorndon", THORNDON[0], THORNDON[1],
                    departure.plusHours(1), null));
            return routeRepository.save(new Route(trip, start, end)).getId();
        });
        routePlanning.plan(id);
        return id;
    }
}
