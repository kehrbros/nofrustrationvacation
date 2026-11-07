package com.nfv.advisor;

import com.jayway.jsonpath.JsonPath;
import com.nfv.assistant.logic.ConversationTool;
import com.nfv.assistant.logic.ToolConversation;
import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.logic.IncidentLogic;
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
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** When the model gets {@code find_overnight_areas} once it is enabled, see Docs/advisor.md#find_overnight_areas. */
@SpringBootTest(properties = "nfv.advisor.stopover-enabled=true")
@AutoConfigureMockMvc
class OvernightToolOfferedTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Incidents stay in the database of the test run and tests use the same roads: each test gets its own days. */
    private static final AtomicInteger TESTS = new AtomicInteger();
    private static final double[] NEWTOWN = {-41.3100, 174.7790};
    private static final double[] THORNDON = {-41.2755, 174.7790};
    private static final String TOOL = "find_overnight_areas";

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
            .plusDays(1200L + 15L * TESTS.incrementAndGet()).withNano(0);
    private final AtomicReference<List<String>> offered = new AtomicReference<>();
    private String username;
    private long userId;

    @BeforeEach
    void setUp() {
        if (regionImport.status("wellington").state() != RegionStatus.State.READY) {
            regionImport.importRegion("wellington");
        }
        username = "overnight-" + UUID.randomUUID();
        userId = inTransaction(() -> appUserProvisioning.findOrCreate(username, null, null).getId());
        when(conversation.run(anyLong(), any())).thenAnswer(call -> {
            ToolConversation.Request request = call.getArgument(1);
            offered.set(request.tools().stream().map(ConversationTool::name).toList());
            return JSON.readTree("{\"summary\":\"Stay.\",\"proposals\":[{\"option_id\":\"keep_route\",\"reason\":\"-\"}]}");
        });
    }

    @Test
    void closureTooLongToWaitIsEnoughEvenWithoutDeadline() throws Exception {
        long routeId = plannedRoute(null);
        // ends after all: without an end it would hit every later route on this road in the database of the test run
        closure(middleSegment(routeId), planned.plusDays(5));

        assertThat(toolsOfferedFor(routeId)).contains(TOOL);
    }

    @Test
    void tooLateIsEnough() throws Exception {
        long routeId = plannedRoute(planned.plusMinutes(30));
        delay(middleSegment(routeId), Duration.ofHours(5));

        assertThat(toolsOfferedFor(routeId)).contains(TOOL);
    }

    @Test
    void delayWithinTheDeadlineIsNot() throws Exception {
        long routeId = plannedRoute(planned.plusHours(6));
        delay(middleSegment(routeId), Duration.ofMinutes(20));

        assertThat(toolsOfferedFor(routeId)).isNotEmpty().doesNotContain(TOOL);
    }

    private List<String> toolsOfferedFor(long routeId) throws Exception {
        RequestPostProcessor user = authentication(new AppUserAuthentication(
                new CurrentUser(userId, username),
                Jwt.withTokenValue("t").header("alg", "none").subject(username).build(), List.of()));
        String started = mockMvc.perform(post("/api/v1/routes/{id}/alternative-proposals", routeId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        Number runId = JsonPath.read(started, "$.runId");
        long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < until) {
            String body = mockMvc.perform(get("/api/v1/alternative-proposals/{id}", runId.longValue()).with(user))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String runStatus = JsonPath.read(body, "$.status");
            if (!runStatus.equals("PENDING") && !runStatus.equals("RUNNING")) {
                assertThat(runStatus).isEqualTo("COMPLETED");
                return offered.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("run did not finish");
    }

    private long plannedRoute(@Nullable OffsetDateTime deadline) {
        long routeId = inTransaction(() -> {
            Trip trip = tripRepository.save(new Trip(userId, "Wellington", planned.minusDays(1), planned.plusDays(7),
                    planned.minusDays(2)));
            Stop start = stopRepository.save(new Stop(trip, 0, "Newtown", NEWTOWN[0], NEWTOWN[1], null, planned));
            Stop end = stopRepository.save(new Stop(trip, 1, "Thorndon", THORNDON[0], THORNDON[1], deadline, null));
            return routeRepository.save(new Route(trip, start, end)).getId();
        });
        routePlanning.plan(routeId);
        return routeId;
    }

    private void closure(long segmentId, OffsetDateTime until) {
        inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "closed", planned.minusHours(1), until, null,
                Set.of(segmentId)));
    }

    private void delay(long segmentId, Duration delay) {
        inTransaction(() -> incidentLogic.create(IncidentType.CONGESTION, "slow", planned.minusHours(1),
                planned.plusHours(3), delay, Set.of(segmentId)));
    }

    private long middleSegment(long routeId) {
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        return sections.get(sections.size() / 2).getSegmentId();
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(_ -> action.get());
    }
}
