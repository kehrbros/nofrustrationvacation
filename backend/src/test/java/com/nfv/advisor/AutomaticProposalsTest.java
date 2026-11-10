package com.nfv.advisor;

import com.nfv.advisor.data.AdvisorRunRepository;
import com.nfv.advisor.entities.AdvisorRun;
import com.nfv.advisor.entities.ProposalGoal;
import com.nfv.advisor.entities.RunStatus;
import com.nfv.advisor.entities.RunTrigger;
import com.nfv.advisor.logic.AlternativeProposals;
import com.nfv.assistant.logic.ToolConversation;
import com.nfv.incidents.data.ConsequenceRepository;
import com.nfv.incidents.entities.Category;
import com.nfv.incidents.entities.Consequence;
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
import com.nfv.trips.logic.NewStop;
import com.nfv.trips.logic.RoutePlanning;
import com.nfv.trips.logic.TripLogic;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which events lead to a run, see Docs/advisor.md#tests. Switched off as everywhere, so nothing races with the test: it
 * hands the recorded events to {@link AlternativeProposals#startAutomatic} itself, which returns when the run has ended.
 * The listener is covered by {@link RouteTooLateListenerTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class AutomaticProposalsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Own days per test, also apart from those of the other tests on these roads. */
    private static final AtomicInteger TESTS = new AtomicInteger();
    private static final double[] NEWTOWN = {-41.3100, 174.7790};
    private static final double[] THORNDON = {-41.2755, 174.7790};

    @MockitoBean
    private ToolConversation conversation;
    @Autowired
    private AlternativeProposals proposals;
    @Autowired
    private AdvisorRunRepository runRepository;
    @Autowired
    private RoutePlanning routePlanning;
    @Autowired
    private RegionImport regionImport;
    @Autowired
    private IncidentLogic incidentLogic;
    @Autowired
    private ConsequenceRepository consequenceRepository;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private TripLogic tripLogic;
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

    private final OffsetDateTime planned = OffsetDateTime.now(ZoneOffset.UTC)
            .plusDays(1000L + 15L * TESTS.incrementAndGet()).withNano(0);
    private long userId;
    private long tripId;
    private long targetStopId;
    private long routeId;

    @BeforeEach
    void setUp() {
        if (regionImport.status("wellington").state() != RegionStatus.State.READY) {
            regionImport.importRegion("wellington");
        }
        routeId = plannedRoute();
        when(conversation.runForSystem(any())).thenReturn(JSON.readTree(
                "{\"summary\":\"Stay.\",\"proposals\":[{\"option_id\":\"keep_route\",\"reason\":\"-\"}]}"));
    }

    @Test
    void runsForTheWorstConsequenceOnTheQuotaOfTheSystem() {
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "Jam", planned.minusHours(1),
                planned.plusHours(3), Duration.ofMinutes(10), Set.of(sections.getFirst().getSegmentId())));
        closure(sections.get(sections.size() / 2).getSegmentId());
        long tooLate = inTransaction(() -> consequenceRepository.findByRouteId(routeId)).stream()
                .filter(c -> c.getCategory() == Category.TOO_LATE).map(Consequence::getId).findFirst().orElseThrow();

        long runId = proposals.startAutomatic(lastEvent()).orElseThrow();

        AdvisorRun run = runRepository.findById(runId).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getTrigger()).isEqualTo(RunTrigger.AUTO);
        assertThat(run.getGoal()).isEqualTo(ProposalGoal.ARRIVE_ON_TIME);
        assertThat(run.getConsequenceId()).isEqualTo(tooLate);
        assertThat(run.getAutoKey()).hasSize(64);
        verify(conversation).runForSystem(any());
        verify(conversation, never()).run(anyLong(), any());
    }

    @Test
    void sameIncidentsAndDeadlineAfterAReplanningStartNoRun() {
        closure(middleSegment());
        assertThat(proposals.startAutomatic(lastEvent())).isPresent();
        long before = onlyConsequence();
        int events = applicationEvents.stream(RouteTooLate.class).toList().size();

        routePlanning.plan(routeId);

        assertThat(onlyConsequence()).as("replanning replaces the consequence").isNotEqualTo(before);
        assertThat(applicationEvents.stream(RouteTooLate.class).toList()).as("published again").hasSizeGreaterThan(events);
        assertThat(proposals.startAutomatic(lastEvent())).isEmpty();
        assertThat(runs()).hasSize(1);
    }

    @Test
    void anotherIncidentStartsARun() {
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        closure(sections.get(sections.size() / 3).getSegmentId());
        long first = proposals.startAutomatic(lastEvent()).orElseThrow();

        // still there after the wait at the closure, which moves the passage by hours
        inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "Jam", planned.minusHours(1),
                planned.plusHours(6), Duration.ofMinutes(10), Set.of(sections.get(2 * sections.size() / 3).getSegmentId())));

        assertThat(proposals.startAutomatic(lastEvent())).isPresent().get().isNotEqualTo(first);
    }

    @Test
    void anotherDeadlineWithoutACategoryChangePublishesNothing() {
        closure(middleSegment());
        int events = applicationEvents.stream(RouteTooLate.class).toList().size();

        arriveAt(planned.plusMinutes(90));

        assertThat(applicationEvents.stream(RouteTooLate.class).toList()).hasSize(events);
    }

    @Test
    void tooLateAgainWithAnotherDeadlineStartsARun() {
        closure(middleSegment());
        long first = proposals.startAutomatic(lastEvent()).orElseThrow();

        arriveAt(planned.plusDays(1));
        arriveAt(planned.plusMinutes(90));

        assertThat(proposals.startAutomatic(lastEvent())).isPresent().get().isNotEqualTo(first);
    }

    @Test
    void tooLateAgainWithTheSameDeadlineStartsNoRun() {
        closure(middleSegment());
        assertThat(proposals.startAutomatic(lastEvent())).isPresent();

        arriveAt(planned.plusDays(1));
        arriveAt(planned.plusHours(1));

        assertThat(proposals.startAutomatic(lastEvent())).isEmpty();
    }

    @Test
    void aFailedRunFreesItsKey() {
        closure(middleSegment());
        RouteTooLate event = lastEvent();
        when(conversation.runForSystem(any())).thenThrow(new IllegalStateException("broken"));
        long failed = proposals.startAutomatic(event).orElseThrow();
        assertThat(runRepository.findById(failed).orElseThrow().getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(runRepository.findById(failed).orElseThrow().getAutoKey()).isNull();

        assertThat(proposals.startAutomatic(event)).isPresent().get().isNotEqualTo(failed);
    }

    @Test
    void anAbandonedRunFreesItsKey() {
        closure(middleSegment());
        RouteTooLate event = lastEvent();
        long first = proposals.startAutomatic(event).orElseThrow();
        String key = runRepository.findById(first).orElseThrow().getAutoKey();
        long stuck = inTransaction(() -> {
            runRepository.deleteById(first);
            runRepository.flush();
            return runRepository.save(new AdvisorRun(routeId, onlyConsequence(), RunTrigger.AUTO,
                    ProposalGoal.ARRIVE_ON_TIME, "x", key, OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(16))).getId();
        });

        assertThat(proposals.startAutomatic(event)).isPresent();

        assertThat(runRepository.findById(stuck).orElseThrow().getFailure()).isEqualTo("ABANDONED");
    }

    @Test
    void duplicateEventsAtTheSameTimeStartOneRun() throws Exception {
        closure(middleSegment());
        RouteTooLate event = lastEvent();
        Callable<Optional<Long>> start = () -> proposals.startAutomatic(event);

        List<Optional<Long>> results;
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Optional<Long>>> futures = threads.invokeAll(IntStream.range(0, 8).mapToObj(_ -> start).toList());
            results = futures.stream().map(Future::resultNow).toList();
        }

        assertThat(results.stream().filter(Optional::isPresent)).hasSize(1);
        assertThat(runs()).hasSize(1);
        verify(conversation, times(1)).runForSystem(any());
    }

    @Test
    void anEventThatIsOutOfDateStartsNothing() {
        closure(middleSegment());
        RouteTooLate event = lastEvent();

        arriveAt(planned.plusDays(1));

        assertThat(proposals.startAutomatic(event)).isEmpty();
        assertThat(runs()).isEmpty();
    }

    @Test
    void anEventOfADeletedRouteStartsNothing() {
        closure(middleSegment());
        RouteTooLate event = lastEvent();

        inTransaction(() -> {
            tripLogic.delete(userId, tripId);
            return null;
        });

        assertThat(proposals.startAutomatic(event)).isEmpty();
    }

    @Test
    void switchedOffNothingStartsByItself() {
        closure(middleSegment());

        assertThat(applicationEvents.stream(RouteTooLate.class)).isNotEmpty();
        verify(conversation, after(500).never()).runForSystem(any());
        assertThat(runs()).isEmpty();
    }

    private void arriveAt(OffsetDateTime arrival) {
        inTransaction(() -> tripLogic.updateStop(userId, tripId, targetStopId, new NewStop("Thorndon", THORNDON[0],
                THORNDON[1], arrival, null), com.nfv.trips.logic.BookingFit.IGNORE));
    }

    private RouteTooLate lastEvent() {
        return applicationEvents.stream(RouteTooLate.class).toList().getLast();
    }

    private List<AdvisorRun> runs() {
        return runRepository.findAll().stream().filter(run -> run.getRouteId() == routeId).toList();
    }

    private long onlyConsequence() {
        return inTransaction(() -> consequenceRepository.findByRouteId(routeId)).getFirst().getId();
    }

    private long middleSegment() {
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        return sections.get(sections.size() / 2).getSegmentId();
    }

    /** Over a wait far past the arrival time, so {@code TOO_LATE}. */
    private void closure(long segmentId) {
        inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "closed", planned.minusHours(1),
                planned.plusHours(3), null, Set.of(segmentId)));
    }

    /** Arrival time one hour after the departure, the route takes minutes. */
    private long plannedRoute() {
        long id = inTransaction(() -> {
            userId = appUserProvisioning.findOrCreate("auto-" + UUID.randomUUID(), null, null).getId();
            Trip trip = tripRepository.save(new Trip(userId, "Wellington", planned.minusDays(1), planned.plusDays(7),
                    planned.minusDays(2)));
            tripId = trip.getId();
            Stop start = stopRepository.save(new Stop(trip, 0, "Newtown", NEWTOWN[0], NEWTOWN[1], null, planned));
            Stop end = stopRepository.save(new Stop(trip, 1, "Thorndon", THORNDON[0], THORNDON[1], planned.plusHours(1),
                    null));
            targetStopId = end.getId();
            return routeRepository.save(new Route(trip, start, end)).getId();
        });
        routePlanning.plan(id);
        return id;
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(_ -> action.get());
    }
}
