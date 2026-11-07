package com.nfv.advisor;

import com.jayway.jsonpath.JsonPath;
import com.nfv.advisor.logic.StayOptions;
import com.nfv.advisor.logic.StayOptions.StayOffer;
import com.nfv.advisor.logic.StayOptions.StaySearch;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A run that proposes a stay, stored and shown with its offer, see Docs/advisor.md#search_accommodations. */
@SpringBootTest(properties = "nfv.advisor.stopover-enabled=true")
@AutoConfigureMockMvc
class StayProposalsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Incidents stay in the database of the test run and tests use the same roads: each test gets its own days. */
    private static final AtomicInteger TESTS = new AtomicInteger();
    private static final double[] NEWTOWN = {-41.3100, 174.7790};
    private static final double[] THORNDON = {-41.2755, 174.7790};
    private static final StayOffer OFFER = new StayOffer("lp1", "offer-1", "Thorndon Lodge", -41.276, 174.778, 120,
            3.0, 8.4, "Room only", new BigDecimal("189.00"), "EUR", true, LocalDateTime.of(2030, 1, 1, 18, 0));

    @MockitoBean
    private ToolConversation conversation;
    @MockitoBean
    private StayOptions stayOptions;
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
            .plusDays(1600L + 15L * TESTS.incrementAndGet()).withNano(0);
    private String username;
    private long userId;

    @BeforeEach
    void setUp() {
        if (regionImport.status("wellington").state() != RegionStatus.State.READY) {
            regionImport.importRegion("wellington");
        }
        username = "stay-" + UUID.randomUUID();
        userId = inTransaction(() -> appUserProvisioning.findOrCreate(username, null, null).getId());
        when(stayOptions.available()).thenReturn(true);
        when(stayOptions.bookedStay(anyLong(), anyLong(), anyLong())).thenReturn(Optional.empty());
        when(stayOptions.search(anyLong(), any())).thenReturn(List.of(OFFER));
    }

    @Test
    void stayAtTheTargetStopIsStoredWithItsOffer() throws Exception {
        long routeId = plannedRoute();
        closure(middleSegment(routeId), planned.plusDays(5));
        String checkIn = planned.toLocalDate().toString();
        when(conversation.run(anyLong(), any())).thenAnswer(call -> {
            ToolConversation.Request request = call.getArgument(1);
            assertThat(request.tools()).extracting(ConversationTool::name).contains("search_accommodations");
            JsonNode found = request.tools().stream().filter(t -> t.name().equals("search_accommodations"))
                    .findFirst().orElseThrow().handler().apply(JSON.readTree("""
                            {"area_id":null,"radius_km":null,"check_in":"%s","check_out":null,"max_price":200,
                            "max_results":null}""".formatted(checkIn)));
            String stay = found.path("offers").get(0).path("option_id").asString();
            return JSON.readTree("""
                    {"summary":"The road stays closed.","proposals":[{"option_id":"%s","reason":"Stay close by."},
                    {"option_id":"keep_route","reason":"Wait."}]}""".formatted(stay));
        });

        String body = finishedRun(routeId);

        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("COMPLETED");
        assertThat((String) JsonPath.read(body, "$.proposals[0].kind")).isEqualTo("TARGET_STAY");
        assertThat((String) JsonPath.read(body, "$.proposals[0].reason")).isEqualTo("Stay close by.");
        assertThat((String) JsonPath.read(body, "$.proposals[0].stay.hotelName")).isEqualTo("Thorndon Lodge");
        assertThat((String) JsonPath.read(body, "$.proposals[0].stay.offerId")).isEqualTo("offer-1");
        assertThat((String) JsonPath.read(body, "$.proposals[0].stay.price")).isEqualTo("189.00");
        assertThat((String) JsonPath.read(body, "$.proposals[0].stay.checkIn")).isEqualTo(checkIn);
        assertThat((String) JsonPath.read(body, "$.proposals[0].stay.place")).isEqualTo("Thorndon");
        assertThat((Integer) JsonPath.read(body, "$.proposals[0].stay.guests")).isEqualTo(3);
        assertThat((Integer) JsonPath.read(body, "$.proposals[0].stay.rooms")).isEqualTo(2);
        assertThat((Object) JsonPath.read(body, "$.proposals[0].stay.replacesBookingId")).isNull();
        assertThat((String) JsonPath.read(body, "$.proposals[1].kind")).isEqualTo("KEEP_ROUTE");
        assertThat((Object) JsonPath.read(body, "$.proposals[1].stay")).isNull();
        ArgumentCaptor<StaySearch> search = ArgumentCaptor.forClass(StaySearch.class);
        verify(stayOptions).search(eq(userId), search.capture());
        assertThat(search.getValue().latitude()).isEqualTo(THORNDON[0]);
        assertThat(search.getValue().maxPrice()).isEqualByComparingTo("200");
    }

    private String finishedRun(long routeId) throws Exception {
        RequestPostProcessor user = authentication(new AppUserAuthentication(
                new CurrentUser(userId, username),
                Jwt.withTokenValue("t").header("alg", "none").subject(username).build(), List.of()));
        String started = mockMvc.perform(post("/api/v1/routes/{id}/alternative-proposals", routeId).with(user)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        Number runId = JsonPath.read(started, "$.runId");
        long until = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < until) {
            String body = mockMvc.perform(get("/api/v1/alternative-proposals/{id}", runId.longValue()).with(user))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String runStatus = JsonPath.read(body, "$.status");
            if (!runStatus.equals("PENDING") && !runStatus.equals("RUNNING")) {
                return body;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("run did not finish");
    }

    private long plannedRoute() {
        long routeId = inTransaction(() -> {
            Trip trip = new Trip(userId, "Wellington", planned.minusDays(1), planned.plusDays(7),
                    planned.minusDays(2));
            trip.changeTravelers(3);
            trip = tripRepository.save(trip);
            Stop start = stopRepository.save(new Stop(trip, 0, "Newtown", NEWTOWN[0], NEWTOWN[1], null, planned));
            Stop end = stopRepository.save(new Stop(trip, 1, "Thorndon", THORNDON[0], THORNDON[1], null, null));
            return routeRepository.save(new Route(trip, start, end)).getId();
        });
        routePlanning.plan(routeId);
        return routeId;
    }

    private void closure(long segmentId, OffsetDateTime until) {
        inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "closed", planned.minusHours(1), until, null,
                Set.of(segmentId)));
    }

    private long middleSegment(long routeId) {
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        return sections.get(sections.size() / 2).getSegmentId();
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(_ -> action.get());
    }
}
