package com.nfv.advisor;

import com.jayway.jsonpath.JsonPath;
import com.nfv.advisor.data.AdvisorRunRepository;
import com.nfv.advisor.entities.AdvisorProposal;
import com.nfv.advisor.entities.AdvisorRun;
import com.nfv.advisor.entities.ProposalGoal;
import com.nfv.advisor.entities.ProposalKind;
import com.nfv.advisor.entities.ProposalPoint;
import com.nfv.advisor.entities.ProposalStay;
import com.nfv.advisor.entities.RunStatus;
import com.nfv.advisor.entities.RunTrigger;
import com.nfv.assistant.logic.ToolConversation;
import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.logic.ActiveIncidents;
import com.nfv.incidents.logic.IncidentLogic;
import com.nfv.incidents.logic.RouteConsequence;
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
import com.nfv.user.logic.AppUserProvisioning;
import com.nfv.user.security.AppUserAuthentication;
import com.nfv.user.security.CurrentUser;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Taking or dismissing the proposals of a run, see Docs/advisor.md#auswählen-und-verwerfen. */
@SpringBootTest
@AutoConfigureMockMvc
class ProposalDecisionsTest {
    /** Incidents stay in the database of the test run and tests use the same roads: each test gets its own days. */
    private static final AtomicInteger TESTS = new AtomicInteger();
    private static final double[] NEWTOWN = {-41.3100, 174.7790};
    private static final double[] THORNDON = {-41.2755, 174.7790};
    private static final double[] TE_ARO = {-41.2930, 174.7760};

    @MockitoBean
    private ToolConversation conversation;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RoutePlanning routePlanning;
    @Autowired
    private RegionImport regionImport;
    @Autowired
    private IncidentLogic incidentLogic;
    @Autowired
    private ActiveIncidents activeIncidents;
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
    private AdvisorRunRepository runRepository;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private final OffsetDateTime planned = OffsetDateTime.now(ZoneOffset.UTC)
            .plusDays(2200L + 15L * TESTS.incrementAndGet()).withHour(8).withMinute(0).withSecond(0).withNano(0);
    private RequestPostProcessor user;
    private long userId;
    private long tripId;
    private long startId;
    private long targetId;
    private long routeId;
    private long incidentId;

    @BeforeEach
    void setUp() {
        if (regionImport.status("wellington").state() != RegionStatus.State.READY) {
            regionImport.loadOrImport("wellington");
        }
        String username = "decisions-" + UUID.randomUUID();
        userId = inTransaction(() -> appUserProvisioning.findOrCreate(username, null, null).getId());
        user = login(userId, username);
        inTransaction(() -> {
            Trip trip = tripRepository.save(new Trip(userId, "Wellington", planned.minusDays(1), planned.plusDays(7),
                    planned.minusDays(2)));
            tripId = trip.getId();
            Stop start = stopRepository.save(new Stop(trip, 0, "Newtown", NEWTOWN[0], NEWTOWN[1], null, planned));
            Stop target = stopRepository.save(new Stop(trip, 1, "Thorndon", THORNDON[0], THORNDON[1],
                    planned.plusHours(1), planned.plusDays(3)));
            startId = start.getId();
            targetId = target.getId();
            routeId = routeRepository.save(new Route(trip, start, target)).getId();
            return null;
        });
        routePlanning.plan(routeId);
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        long segmentId = sections.get(sections.size() / 2).getSegmentId();
        incidentId = inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "closed", planned.minusHours(1),
                planned.plusDays(5), null, Set.of(segmentId)).getId());
    }

    @Test
    void stopoverAddsAStopAtTheHotelAndMovesTheArrival() throws Exception {
        long bookingId = booking(targetId, planned.toLocalDate());
        LocalDate checkIn = planned.toLocalDate();
        long runId = run(RunStatus.COMPLETED, proposal(0, ProposalKind.STOPOVER, null, planned.plusMinutes(30),
                List.of(), stay(checkIn, null)), proposal(1, ProposalKind.KEEP_ROUTE, null, null, List.of(), null));

        String accepted = mockMvc.perform(post("/api/v1/alternative-proposals/{id}/proposals/{rank}/acceptance",
                        runId, 1).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("STOPOVER"))
                .andExpect(jsonPath("$.tripId").value(tripId))
                .andExpect(jsonPath("$.replacesBookingId").doesNotExist())
                .andExpect(jsonPath("$.todosAdded").value(1))
                .andReturn().getResponse().getContentAsString();

        List<Stop> stops = inTransaction(() -> stopRepository.findByTripIdOrderByPosition(tripId));
        assertThat(stops).extracting(Stop::getName).containsExactly("Newtown", "Te Aro", "Thorndon");
        Stop night = stops.get(1);
        assertThat(((Number) JsonPath.read(accepted, "$.stayStopId")).longValue()).isEqualTo(night.getId());
        assertThat(night.getLatitude()).isEqualTo(TE_ARO[0]);
        assertThat(night.getArrivalAt()).isEqualTo(planned.plusMinutes(30));
        assertThat(night.getDepartureAt()).isEqualTo(OffsetDateTime.of(checkIn.plusDays(1), LocalTime.of(9, 0),
                ZoneOffset.UTC));
        assertThat(stops.get(2).getArrivalAt()).isEqualTo(planned.plusHours(1).plusDays(1));
        List<Number> planned = JsonPath.read(accepted, "$.plannedRouteIds");
        assertThat(planned).hasSize(2);
        // the route was split, the run went with it
        assertThat(inTransaction(() -> routeRepository.findById(routeId))).isEmpty();
        assertThat(inTransaction(() -> runRepository.findById(runId))).isEmpty();
        // the accommodation at the target stop expects the user a day earlier
        mockMvc.perform(get("/api/v1/trips/{id}/booking-todos", tripId).with(user))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].kind").value("NOTIFY_LATE_ARRIVAL"))
                .andExpect(jsonPath("$[0].reason").value("ARRIVAL_LATER"))
                .andExpect(jsonPath("$[0].bookingId").value(bookingId))
                .andExpect(jsonPath("$[0].newArrivalOn").value(checkIn.plusDays(1).toString()));
    }

    @Test
    void targetStayLeadsToBookingInsteadOfTheBookedAccommodation() throws Exception {
        long bookingId = booking(targetId, planned.toLocalDate());
        long runId = run(RunStatus.COMPLETED, proposal(0, ProposalKind.TARGET_STAY, null, planned.plusHours(3),
                List.of(), stay(planned.toLocalDate(), bookingId)));

        mockMvc.perform(post("/api/v1/alternative-proposals/{id}/proposals/{rank}/acceptance", runId, 1)
                        .with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("TARGET_STAY"))
                .andExpect(jsonPath("$.stayStopId").value(targetId))
                .andExpect(jsonPath("$.replacesBookingId").value(bookingId))
                .andExpect(jsonPath("$.plannedRouteIds", hasSize(0)));
        mockMvc.perform(get("/api/v1/alternative-proposals/{id}", runId).with(user))
                .andExpect(jsonPath("$.decision").value("ACCEPTED"))
                .andExpect(jsonPath("$.chosenRank").value(1))
                .andExpect(jsonPath("$.incidentId").value(incidentId));
        // nothing obsolete before the new hotel is booked
        mockMvc.perform(get("/api/v1/trips/{id}/booking-todos", tripId).with(user))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void shiftedDepartureChangesTheDepartureAtTheStart() throws Exception {
        long runId = run(RunStatus.COMPLETED, proposal(0, ProposalKind.SHIFT_DEPARTURE, planned.plusHours(6),
                planned.plusHours(6).plusMinutes(20), List.of(), null));

        mockMvc.perform(post("/api/v1/alternative-proposals/{id}/proposals/{rank}/acceptance", runId, 1)
                        .with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedRouteIds[0]").value(routeId));

        Stop start = inTransaction(() -> stopRepository.findById(startId).orElseThrow());
        assertThat(start.getDepartureAt()).isEqualTo(planned.plusHours(6));
    }

    @Test
    void rerouteSetsThePointsOfTheProposalAsWaypoints() throws Exception {
        long runId = run(RunStatus.COMPLETED, proposal(0, ProposalKind.REROUTE, planned, planned.plusMinutes(25),
                List.of(new ProposalPoint(TE_ARO[0], TE_ARO[1], "Willis Street", null)), null));

        mockMvc.perform(post("/api/v1/alternative-proposals/{id}/proposals/{rank}/acceptance", runId, 1)
                        .with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("REROUTE"));

        List<Waypoint> waypoints = inTransaction(() -> waypointRepository.findByRouteIdOrderByPosition(routeId));
        assertThat(waypoints).hasSize(1);
        assertThat(waypoints.getFirst().getLatitude()).isEqualTo(TE_ARO[0]);
        assertThat(waypoints.getFirst().getOrigin()).isEqualTo(WaypointOrigin.SUGGESTION);
    }

    @Test
    void dismissingReplacesTheDecisionAndTheTripShowsTheLastRun() throws Exception {
        long runId = run(RunStatus.COMPLETED, proposal(0, ProposalKind.KEEP_ROUTE, null, null, List.of(), null));

        mockMvc.perform(post("/api/v1/alternative-proposals/{id}/proposals/{rank}/acceptance", runId, 1)
                        .with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedRouteIds", hasSize(0)));
        mockMvc.perform(post("/api/v1/alternative-proposals/{id}/dismissal", runId).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("DISMISSED"))
                .andExpect(jsonPath("$.chosenRank").doesNotExist())
                .andExpect(jsonPath("$.decidedAt").exists());

        mockMvc.perform(get("/api/v1/trips/{id}/alternative-proposals", tripId).with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runs", hasSize(1)))
                .andExpect(jsonPath("$.runs[0].runId").value(runId))
                .andExpect(jsonPath("$.runs[0].decision").value("DISMISSED"));
    }

    @Test
    void onlyFinishedRunsOfTheUserCanBeTaken() throws Exception {
        long pending = run(RunStatus.PENDING);
        mockMvc.perform(post("/api/v1/alternative-proposals/{id}/proposals/{rank}/acceptance", pending, 1)
                        .with(user).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROPOSAL_NOT_APPLICABLE"));

        long runId = run(RunStatus.COMPLETED, proposal(0, ProposalKind.KEEP_ROUTE, null, null, List.of(), null));
        mockMvc.perform(post("/api/v1/alternative-proposals/{id}/proposals/{rank}/acceptance", runId, 2)
                        .with(user).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PROPOSAL_INPUT"));

        String other = "decisions-other-" + UUID.randomUUID();
        RequestPostProcessor otherUser = login(inTransaction(() -> appUserProvisioning.findOrCreate(other, null, null)
                .getId()), other);
        mockMvc.perform(post("/api/v1/alternative-proposals/{id}/proposals/{rank}/acceptance", runId, 1)
                        .with(otherUser).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/alternative-proposals/{id}/dismissal", runId).with(otherUser).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/trips/{id}/alternative-proposals", tripId).with(otherUser))
                .andExpect(status().isNotFound());
    }

    @Test
    void runForAnIncidentRemembersIt() throws Exception {
        org.mockito.Mockito.when(conversation.run(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any())).thenThrow(new com.nfv.assistant.logic.AssistantUnavailableException(
                "no model in tests"));
        String started = mockMvc.perform(post("/api/v1/routes/{id}/alternative-proposals", routeId).with(user)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"incidentId\":" + incidentId + "}"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        long runId
 = ((Number) JsonPath.read(started, "$.runId")).longValue();
        assertThat(inTransaction(() -> runRepository.findById(runId).orElseThrow().getIncidentId()))
                .isEqualTo(incidentId);
        mockMvc.perform(post("/api/v1/routes/{id}/alternative-proposals", routeId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"incidentId\":-1}"))
                .andExpect(status().isBadRequest());
        // let the run end before the next test
        long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < until
                && inTransaction(() -> runRepository.findById(runId).orElseThrow().isUnfinished())) {
            Thread.sleep(50);
        }
    }

    private long run(RunStatus status, AdvisorProposal... proposals) {
        RouteConsequence consequence = activeIncidents.consequencesOnRoute(routeId).getFirst();
        return inTransaction(() -> {
            AdvisorRun run = new AdvisorRun(routeId, consequence.id(), consequence.incidentId(), RunTrigger.USER,
                    ProposalGoal.ARRIVE_ON_TIME, "situation", null, OffsetDateTime.now());
            if (status != RunStatus.PENDING) {
                run.started();
                run.completed(status, "summary", List.of(proposals), OffsetDateTime.now());
            }
            return runRepository.save(run).getId();
        });
    }

    private static AdvisorProposal proposal(int position, ProposalKind kind, @Nullable OffsetDateTime departureAt,
                                            @Nullable OffsetDateTime arrivalAt, List<ProposalPoint> points,
                                            @Nullable ProposalStay stay) {
        return new AdvisorProposal(position, kind, "reason", null, departureAt, arrivalAt, 4000.0,
                Duration.ofMinutes(20), Duration.ofMinutes(5), 0.8, true, points, List.of(), stay);
    }

    private static ProposalStay stay(LocalDate checkIn, @Nullable Long replacesBookingId) {
        return new ProposalStay("lp1", "offer-1", "Te Aro Lodge", TE_ARO[0], TE_ARO[1], "Te Aro", 200, checkIn,
                checkIn.plusDays(1), 2, 1, new BigDecimal("150.00"), "EUR", true, null, replacesBookingId);
    }

    private long booking(long stopId, LocalDate checkIn) throws Exception {
        String json = "{\"source\":\"DOCUMENT\",\"name\":\"Thorndon Hotel\",\"latitude\":" + THORNDON[0]
                + ",\"longitude\":" + THORNDON[1] + ",\"provider\":\"Booking.com\",\"reference\":\"4711\","
                + "\"checkIn\":\"" + checkIn.atTime(15, 0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "\","
                + "\"checkOut\":\"" + checkIn.plusDays(3).atTime(10, 0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                + "\",\"guests\":2,\"rooms\":1}";
        String body = mockMvc.perform(post("/api/v1/stops/{id}/accommodation-bookings", stopId).with(user)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private static RequestPostProcessor login(long appUserId, String username) {
        return authentication(new AppUserAuthentication(new CurrentUser(appUserId, username),
                Jwt.withTokenValue("t").header("alg", "none").subject(username).build(), List.of()));
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(_ -> action.get());
    }
}
