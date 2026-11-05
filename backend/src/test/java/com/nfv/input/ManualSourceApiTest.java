package com.nfv.input;

import com.jayway.jsonpath.JsonPath;
import com.nfv.input.logic.TrafficEventProcessor;
import com.nfv.input.logic.TrafficPolling;
import com.nfv.input.sources.nz.NztaSource;
import com.nfv.network.logic.RegionImport;
import com.nfv.network.logic.RegionStatus;
import com.nfv.trips.data.RouteRepository;
import com.nfv.trips.data.StopRepository;
import com.nfv.trips.data.TripRepository;
import com.nfv.trips.entities.PlanOutcome;
import com.nfv.trips.entities.Route;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.entities.Trip;
import com.nfv.trips.logic.RoutePlanning;
import com.nfv.user.entities.AppUser;
import com.nfv.user.logic.AppUserProvisioning;
import com.nfv.user.security.AppUserAuthentication;
import com.nfv.user.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Events entered by hand through the normal flow, on a route through the Terrace Tunnel, see
 * Docs/traffic-feed.md#testquelle-manual.
 */
@SpringBootTest(properties = {"nfv.input.sources.manual.enabled=true",
        "nfv.input.sources.manual.network-region=wellington",
        // as in the running app: the background thread may process an event before the request does
        "nfv.input.processing.after-poll=true"})
@AutoConfigureMockMvc
class ManualSourceApiTest {
    private static final String EVENTS = "/api/v1/test/traffic-events";
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
    private static final String TUNNEL = "\"point\": {\"lat\": -41.289262576382875, \"lon\": 174.77157677044664}, "
            + "\"roadRef\": \"01N\"";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RegionImport regionImport;
    @Autowired
    private RoutePlanning routePlanning;
    @Autowired
    private TripRepository tripRepository;
    @Autowired
    private StopRepository stopRepository;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TrafficPolling polling;
    @Autowired
    private TrafficEventProcessor processor;

    private RequestPostProcessor tester;
    private String id;

    @BeforeEach
    void setUp() {
        if (regionImport.status("wellington").state() != RegionStatus.State.READY) {
            regionImport.importRegion("wellington");
        }
        // incidents of other test classes in the shared database must not move or block these routes
        jdbc.update("UPDATE incident SET valid_to = valid_from");
        tester = login(List.of(new SimpleGrantedAuthority("ROLE_tester")));
        id = "test-" + UUID.randomUUID();
    }

    @Test
    void closureWithTimeToSpareIsDelayed() throws Exception {
        long routeId = plannedRoute(DEPARTURE.plusHours(3));

        report(closure(DEPARTURE.minusHours(1), DEPARTURE.plusHours(1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("manual"))
                .andExpect(jsonPath("$.outcome").value("INCIDENT"))
                .andExpect(jsonPath("$.incident.type").value("CLOSURE"))
                .andExpect(jsonPath("$.incident.impact").value("BLOCKED"))
                .andExpect(jsonPath("$.incident.segments").isNumber())
                .andExpect(jsonPath("$.incident.roads[*].ref", hasItem("SH 1")))
                .andExpect(jsonPath(consequence(routeId, "category"), hasItem("DELAYED")))
                .andExpect(jsonPath(consequence(routeId, "match"), hasItem("DIRECT")))
                .andExpect(jsonPath(consequence(routeId, "delayAssumed"), hasItem(false)));
    }

    @Test
    void closureWithTooLittleTimeIsTooLate() throws Exception {
        long routeId = plannedRoute(DEPARTURE.plusMinutes(30));

        report(closure(DEPARTURE.minusHours(1), DEPARTURE.plusHours(1)))
                .andExpect(jsonPath(consequence(routeId, "category"), hasItem("TOO_LATE")));
    }

    @Test
    void closureWithoutEndIsNotPassable() throws Exception {
        long routeId = plannedRoute(DEPARTURE.plusHours(3));

        report(closure(DEPARTURE.minusHours(1), null))
                .andExpect(jsonPath("$.incident.validTo").doesNotExist())
                .andExpect(jsonPath(consequence(routeId, "category"), hasItem("TOO_LATE")));
    }

    @Test
    void sameIdChangesTheEventAndResolvedEndsIt() throws Exception {
        long routeId = plannedRoute(DEPARTURE.plusHours(3));
        String first = report(closure(DEPARTURE.minusHours(1), DEPARTURE.plusHours(1)))
                .andReturn().getResponse().getContentAsString();
        int incidentId = JsonPath.read(first, "$.incident.id");

        report(event("\"type\": \"CONGESTION\", " + TUNNEL + ", \"validFrom\": \"" + DEPARTURE.minusHours(1)
                + "\", \"validTo\": \"" + DEPARTURE.plusHours(1) + "\", \"delayMinutes\": 10"))
                .andExpect(jsonPath("$.incident.id").value(incidentId))
                .andExpect(jsonPath("$.incident.type").value("CONGESTION"))
                .andExpect(jsonPath(consequence(routeId, "assessedDelayMinutes"), hasItem(10)));

        report(event("\"type\": \"CONGESTION\", " + TUNNEL + ", \"validFrom\": \"" + DEPARTURE.minusHours(1)
                + "\", \"resolved\": true"))
                .andExpect(jsonPath("$.incident.id").value(incidentId))
                .andExpect(jsonPath(consequence(routeId, "category"), empty()));
    }

    @Test
    void directionKeepsOtherDirectionsOut() throws Exception {
        long routeId = plannedRoute(DEPARTURE.plusHours(3));

        report(event("\"type\": \"CLOSURE\", " + TUNNEL + ", \"direction\": \"SOUTHBOUND\", \"validFrom\": \""
                + DEPARTURE.minusHours(1) + "\", \"validTo\": \"" + DEPARTURE.plusHours(1) + "\""))
                .andExpect(jsonPath("$.outcome").value("INCIDENT"))
                .andExpect(jsonPath(consequence(routeId, "category"), empty()));

        report(event("\"type\": \"CLOSURE\", " + TUNNEL + ", \"direction\": \"NORTHBOUND\", \"validFrom\": \""
                + DEPARTURE.minusHours(1) + "\", \"validTo\": \"" + DEPARTURE.plusHours(1) + "\""))
                .andExpect(jsonPath(consequence(routeId, "category"), hasItem("DELAYED")));
    }

    @Test
    void windowsLimitTheClosure() throws Exception {
        long routeId = plannedRoute(DEPARTURE.plusHours(3));
        OffsetDateTime later = DEPARTURE.plusHours(6);

        report(event("\"type\": \"CLOSURE\", " + TUNNEL + ", \"validFrom\": \"" + DEPARTURE.minusDays(1)
                + "\", \"validTo\": \"" + DEPARTURE.plusDays(1) + "\", \"windows\": [{\"start\": \"" + later
                + "\", \"end\": \"" + later.plusHours(1) + "\"}], \"text\": \"closed at 9pm\""))
                .andExpect(jsonPath("$.note").doesNotExist())
                .andExpect(jsonPath("$.incident.type").value("CLOSURE"))
                .andExpect(jsonPath("$.incident.windows.length()").value(1))
                .andExpect(jsonPath(consequence(routeId, "category"), empty()));

        report(event("\"type\": \"CLOSURE\", " + TUNNEL + ", \"validFrom\": \"" + DEPARTURE.minusDays(1)
                + "\", \"validTo\": \"" + DEPARTURE.plusDays(1) + "\", \"windows\": [{\"start\": \""
                + DEPARTURE.minusMinutes(10) + "\", \"end\": \"" + DEPARTURE.plusHours(1) + "\"}]"))
                .andExpect(jsonPath(consequence(routeId, "category"), hasItem("DELAYED")))
                .andExpect(jsonPath(consequence(routeId, "windowStart")).isNotEmpty());
    }

    @Test
    void timesInTextWithoutWindowsAreUnconfirmed() throws Exception {
        long routeId = plannedRoute(DEPARTURE.plusMinutes(30));

        report(event("\"type\": \"CLOSURE\", " + TUNNEL + ", \"validFrom\": \"" + DEPARTURE.minusHours(1)
                + "\", \"validTo\": \"" + DEPARTURE.plusHours(1) + "\", \"text\": \"closed between 9pm and 4am\""))
                .andExpect(jsonPath("$.note").value(org.hamcrest.Matchers.startsWith("TIMES_IN_TEXT")))
                .andExpect(jsonPath("$.incident.type").value("OTHER"))
                .andExpect(jsonPath("$.incident.timesUnconfirmed").value(true))
                .andExpect(jsonPath(consequence(routeId, "category"), hasItem("DELAYED")));
    }

    @Test
    void heavyVehiclesOnlyHasNoImpact() throws Exception {
        long routeId = plannedRoute(DEPARTURE.plusHours(3));

        report(event("\"type\": \"CLOSURE\", " + TUNNEL + ", \"vehicleScope\": \"HEAVY_ONLY\", \"validFrom\": \""
                + DEPARTURE.minusHours(1) + "\", \"validTo\": \"" + DEPARTURE.plusHours(1) + "\""))
                .andExpect(jsonPath(consequence(routeId, "category"), hasItem("NO_IMPACT")));
    }

    @Test
    void answerShowsTheResultWhoeverProcessedTheEvent() throws Exception {
        for (int i = 0; i < 30; i++) {
            report(closure(DEPARTURE.minusHours(1), DEPARTURE.plusHours(1)))
                    .andExpect(jsonPath("$.outcome").value("INCIDENT"))
                    .andExpect(jsonPath("$.incident.type").value("CLOSURE"));
        }
    }

    @Test
    void placeWithoutRoadSaysSo() throws Exception {
        report(event("\"type\": \"CLOSURE\", \"point\": {\"lat\": -41.40, \"lon\": 174.55}, \"validFrom\": \""
                + DEPARTURE + "\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("NO_SEGMENTS"))
                .andExpect(jsonPath("$.incident").doesNotExist());
    }

    @Test
    void statusShowsTheLatestVersionOfAnySource() throws Exception {
        report(closure(DEPARTURE.minusHours(1), DEPARTURE.plusHours(1)));
        mockMvc.perform(get(EVENTS + "/{id}", id).with(tester))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.outcome").value("INCIDENT"));
        mockMvc.perform(get(EVENTS + "/unknown").with(tester)).andExpect(status().isNotFound());

        polling.pollNow(NztaSource.NAME);
        processor.processPending();
        mockMvc.perform(get(EVENTS + "/566003").param("source", NztaSource.NAME).with(tester))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value(NztaSource.NAME))
                .andExpect(jsonPath("$.incident.roads[*].ref", hasItem("SH 1")));
    }

    @Test
    void invalidEventsAreRejected() throws Exception {
        for (String body : List.of(
                "{\"type\": \"CLOSURE\", " + TUNNEL + ", \"validFrom\": \"" + DEPARTURE + "\"}",
                event("\"type\": \"CLOSURE\", \"validFrom\": \"" + DEPARTURE + "\""),
                event("\"type\": \"CLOSURE\", " + TUNNEL),
                event("\"type\": \"NOPE\", " + TUNNEL + ", \"validFrom\": \"" + DEPARTURE + "\""),
                event("\"type\": \"CLOSURE\", " + TUNNEL + ", \"validFrom\": \"" + DEPARTURE + "\", \"validTo\": \""
                        + DEPARTURE.minusHours(1) + "\""),
                event("\"type\": \"CLOSURE\", " + TUNNEL + ", \"validFrom\": \"" + DEPARTURE
                        + "\", \"windows\": [{\"start\": \"" + DEPARTURE + "\", \"end\": \"" + DEPARTURE + "\"}]"),
                "not json")) {
            report(body).andExpect(status().isBadRequest());
        }
    }

    @Test
    void onlyTestersMayUseIt() throws Exception {
        RequestPostProcessor user = login(List.of(new SimpleGrantedAuthority("ROLE_user")));
        String body = closure(DEPARTURE, DEPARTURE.plusHours(1));

        mockMvc.perform(post(EVENTS).contentType(MediaType.APPLICATION_JSON).content(body).with(user))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(EVENTS + "/{id}", id).with(user)).andExpect(status().isForbidden());
        mockMvc.perform(post(EVENTS).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM input_event WHERE subject_id = ?", Long.class, id))
                .isZero();
    }

    private ResultActions report(String body) throws Exception {
        return mockMvc.perform(post(EVENTS).contentType(MediaType.APPLICATION_JSON).content(body).with(tester));
    }

    private String closure(OffsetDateTime from, OffsetDateTime to) {
        return event("\"type\": \"CLOSURE\", " + TUNNEL + ", \"validFrom\": \"" + from + "\""
                + (to == null ? "" : ", \"validTo\": \"" + to + "\""));
    }

    private String event(String fields) {
        return "{\"id\": \"" + id + "\", " + fields + "}";
    }

    private static String consequence(long routeId, String field) {
        return "$.incident.consequences[?(@.routeId == " + routeId + ")]." + field;
    }

    /** Te Aro → Thorndon through the Terrace Tunnel (northbound), leaving at {@link #DEPARTURE}. */
    private long plannedRoute(OffsetDateTime arrival) {
        long routeId = transactionTemplate.execute(_ -> {
            long userId = appUserProvisioning.findOrCreate("manual-" + UUID.randomUUID(), null, null).getId();
            Trip trip = tripRepository.save(new Trip(userId, "Wellington", DEPARTURE, DEPARTURE.plusDays(1),
                    OffsetDateTime.now(ZoneOffset.UTC)));
            Stop start = stopRepository.save(new Stop(trip, 0, "Te Aro", -41.2947, 174.7735, null, DEPARTURE));
            Stop end = stopRepository.save(new Stop(trip, 1, "Thorndon", -41.2755, 174.7790, arrival, null));
            return routeRepository.save(new Route(trip, start, end)).getId();
        });
        assertThat(routePlanning.plan(routeId).outcome()).isEqualTo(PlanOutcome.PLANNED);
        return routeId;
    }

    private RequestPostProcessor login(List<GrantedAuthority> authorities) {
        String username = "manual-" + UUID.randomUUID();
        AppUser appUser = appUserProvisioning.findOrCreate(username, null, null);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(username).build();
        return authentication(new AppUserAuthentication(new CurrentUser(appUser.getId(), username), jwt, authorities));
    }
}
