package com.nfv.recommendations;

import com.jayway.jsonpath.JsonPath;
import com.nfv.incidents.data.ConsequenceRepository;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Suggestions API: suggest, choose, route lines, foreign routes, see Docs/recommendations.md#api. */
@SpringBootTest
@AutoConfigureMockMvc
class SuggestionApiTest {
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).withNano(0);
    private static final double[] NEWTOWN = {-41.3100, 174.7790};
    private static final double[] THORNDON = {-41.2755, 174.7790};
    private static final double[] KELBURN = {-41.2870, 174.7680};

    @Autowired
    private MockMvc mockMvc;
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
    private RequestPostProcessor user;
    private RequestPostProcessor otherUser;

    @BeforeEach
    void setUp() {
        if (regionImport.status("wellington").state() != RegionStatus.State.READY) {
            regionImport.importRegion("wellington");
        }
        String name = "suggestions-" + UUID.randomUUID();
        user = login(name);
        userId = inTransaction(() -> appUserProvisioning.findOrCreate(name, null, null).getId());
        otherUser = login("suggestions-other-" + UUID.randomUUID());
    }

    @Test
    void suggestionsAreComputedWithoutStoring() throws Exception {
        long routeId = plannedRoute();

        suggest(routeId, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value(routeId))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.waypointMode").value("IGNORE"))
                .andExpect(jsonPath("$.departureAt").isString())
                .andExpect(jsonPath("$.options[0].rank").value(1))
                .andExpect(jsonPath("$.options[0].id").doesNotExist())
                .andExpect(jsonPath("$.options[0].labels[0]").value("FASTEST"))
                .andExpect(jsonPath("$.options[0].geometry[0].latitude").isNumber())
                .andExpect(jsonPath("$.options[0].waypoints").isArray())
                .andExpect(jsonPath("$.reference.arrivalAt").isString());
        suggest(routeId, null).andExpect(status().isOk()).andExpect(jsonPath("$.options").isNotEmpty());
    }

    @Test
    void missingDepartureIsAProblemNotAnError() throws Exception {
        long routeId = newRoute(null);

        suggest(routeId, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options").isEmpty())
                .andExpect(jsonPath("$.problem").value("MISSING_DEPARTURE"));
        suggest(routeId, "{\"departureAt\":\"" + DEPARTURE + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.problem").doesNotExist())
                .andExpect(jsonPath("$.options").isNotEmpty());
    }

    @Test
    void invalidCountIsRejected() throws Exception {
        suggest(plannedRoute(), "{\"count\":9}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUGGESTION_INPUT"));
    }

    @Test
    void foreignRoutesAreNotFound() throws Exception {
        long routeId = plannedRoute();

        mockMvc.perform(post("/api/v1/routes/{id}/suggestions", routeId).with(otherUser).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        choose(otherUser, routeId, "[]")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
    }

    /** IGNORE: the waypoint of the user is replaced by the waypoints of the chosen suggestion, the route planned. */
    @Test
    void chooseReplacesWaypointsAndPlansTheRoute() throws Exception {
        long routeId = newRoute(DEPARTURE);
        long kelburn = inTransaction(() -> waypointRepository.save(new Waypoint(routeRepository.findById(routeId)
                .orElseThrow(), 0, KELBURN[0], KELBURN[1], "Kelburn", null, WaypointOrigin.USER)).getId());
        routePlanning.plan(routeId);
        String body = suggest(routeId, "{}").andReturn().getResponse().getContentAsString();
        List<?> chosen = JsonPath.read(body, "$.options[-1].waypoints");
        String waypoints = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(chosen);

        choose(user, routeId, waypoints)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value(routeId))
                .andExpect(jsonPath("$.planOutcome").value("PLANNED"));

        List<Waypoint> stored = waypointRepository.findByRouteIdOrderByPosition(routeId);
        assertThat(stored).extracting(Waypoint::getId).doesNotContain(kelburn);
        assertThat(stored).hasSize(chosen.size())
                .allSatisfy(w -> assertThat(w.getOrigin()).isEqualTo(WaypointOrigin.SUGGESTION));
    }

    /** FOLLOW: the waypoint of the user stays with its id, name and origin. */
    @Test
    void chooseKeepsWaypointsOfTheUser() throws Exception {
        long routeId = newRoute(DEPARTURE);
        long kelburn = inTransaction(() -> waypointRepository.save(new Waypoint(routeRepository.findById(routeId)
                .orElseThrow(), 0, KELBURN[0], KELBURN[1], "Kelburn", null, WaypointOrigin.USER)).getId());
        routePlanning.plan(routeId);

        choose(user, routeId, "[{\"latitude\":0,\"longitude\":0,\"waypointId\":" + kelburn + "}]")
                .andExpect(status().isOk());

        assertThat(waypointRepository.findByRouteIdOrderByPosition(routeId)).singleElement().satisfies(w -> {
            assertThat(w.getId()).isEqualTo(kelburn);
            assertThat(w.getName()).isEqualTo("Kelburn");
            assertThat(w.getLatitude()).isEqualTo(KELBURN[0]);
            assertThat(w.getOrigin()).isEqualTo(WaypointOrigin.USER);
        });
        choose(user, routeId, "[{\"latitude\":0,\"longitude\":0,\"waypointId\":999999}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUGGESTION_INPUT"));
        choose(user, routeId, "[{\"latitude\":91,\"longitude\":0}]")
                .andExpect(status().isBadRequest());
    }

    /** Choosing a suggestion around a closure: afterwards the consequence of the closure is gone. */
    @Test
    void chooseAroundClosureRemovesTheConsequence() throws Exception {
        long routeId = plannedRoute();
        long closed = middleSegment(routeId);
        inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "closed", DEPARTURE.minusHours(1),
                DEPARTURE.plusHours(3), null, Set.of(closed)));
        assertThat(consequenceRepository.findByRouteId(routeId)).isNotEmpty();
        String body = suggest(routeId, "{}")
                .andExpect(jsonPath("$.reference.blocked").value(true))
                .andReturn().getResponse().getContentAsString();
        List<?> chosen = JsonPath.read(body, "$.options[0].waypoints");

        choose(user, routeId, tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(chosen))
                .andExpect(jsonPath("$.planOutcome").value("PLANNED"));

        assertThat(routeSectionRepository.findByRouteIdOrderByPosition(routeId))
                .extracting(RouteSection::getSegmentId).doesNotContain(closed);
        assertThat(consequenceRepository.findByRouteId(routeId)).isEmpty();
    }

    @Test
    void routeLinesShowThePlannedWays() throws Exception {
        long routeId = plannedRoute();
        long tripId = routeRepository.findById(routeId).orElseThrow().getTrip().getId();
        long unplanned = inTransaction(() -> {
            Trip trip = tripRepository.findById(tripId).orElseThrow();
            Stop end = stopRepository.findByTripIdOrderByPosition(tripId).getLast();
            Stop next = stopRepository.save(new Stop(trip, 2, "Kelburn", KELBURN[0], KELBURN[1], null, null));
            return routeRepository.save(new Route(trip, end, next)).getId();
        });

        mockMvc.perform(get("/api/v1/trips/{id}/route-lines", tripId).with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].routeId").value(routeId))
                .andExpect(jsonPath("$[0].geometry.length()").value(org.hamcrest.Matchers.greaterThan(10)))
                .andExpect(jsonPath("$[0].distanceMeters").value(org.hamcrest.Matchers.greaterThan(3000.0)))
                .andExpect(jsonPath("$[0].durationSeconds").value(org.hamcrest.Matchers.greaterThan(60)));
        assertThat(unplanned).isPositive();
        mockMvc.perform(get("/api/v1/trips/{id}/route-lines", tripId).with(otherUser))
                .andExpect(status().isNotFound());
    }

    @Test
    void choiceOnTheLastRouteOfARoundTripIsPlanned() throws Exception {
        // last stop at the same place as the first, report of 2026-11-05
        long[] routes = roundTrip(DEPARTURE.plusDays(2));
        for (long routeId : routes) {
            routePlanning.plan(routeId);
        }
        long last = routes[1];
        String body = suggest(last, "{}").andReturn().getResponse().getContentAsString();
        String waypoints = JsonPath.parse(JsonPath.<Object>read(body, "$.options[-1:][0].waypoints")).jsonString();

        choose(user, last, waypoints)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planOutcome").value("PLANNED"));
        assertThat(routeSectionRepository.findByRouteIdOrderByPosition(last)).isNotEmpty();
    }

    @Test
    void choiceWithoutDepartureIsStoredButNotPlanned() throws Exception {
        long last = roundTrip(null)[1];
        String body = suggest(last, "{\"departureAt\":\"" + DEPARTURE.plusDays(2) + "\"}").andReturn().getResponse()
                .getContentAsString();
        String waypoints = JsonPath.parse(JsonPath.<Object>read(body, "$.options[-1:][0].waypoints")).jsonString();

        // the frontend shows that the route is planned once the stop has a departure
        choose(user, last, waypoints)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planOutcome").value("MISSING_DEPARTURE"));
        assertThat(routeSectionRepository.findByRouteIdOrderByPosition(last)).isEmpty();
        assertThat(waypointRepository.findByRouteIdOrderByPosition(last))
                .hasSize(JsonPath.<List<Object>>read(waypoints, "$").size());
    }

    /** Newtown → Kelburn → Newtown, departure at Kelburn as given; ids of both routes. */
    private long[] roundTrip(OffsetDateTime departureAtKelburn) {
        return inTransaction(() -> {
            Trip trip = tripRepository.save(new Trip(userId, "Rundreise", DEPARTURE, DEPARTURE.plusDays(7),
                    DEPARTURE.minusDays(2)));
            Stop home = stopRepository.save(new Stop(trip, 0, "Newtown", NEWTOWN[0], NEWTOWN[1], null, DEPARTURE));
            Stop kelburn = stopRepository.save(new Stop(trip, 1, "Kelburn", KELBURN[0], KELBURN[1],
                    DEPARTURE.plusHours(1), departureAtKelburn));
            Stop back = stopRepository.save(new Stop(trip, 2, "Newtown", NEWTOWN[0], NEWTOWN[1], null, null));
            return new long[]{routeRepository.save(new Route(trip, home, kelburn)).getId(),
                    routeRepository.save(new Route(trip, kelburn, back)).getId()};
        });
    }

    private org.springframework.test.web.servlet.ResultActions suggest(long routeId, String json) throws Exception {
        var request = post("/api/v1/routes/{id}/suggestions", routeId).with(user).with(csrf());
        return mockMvc.perform(json == null ? request
                : request.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private org.springframework.test.web.servlet.ResultActions choose(RequestPostProcessor who, long routeId,
                                                                      String waypoints) throws Exception {
        return mockMvc.perform(put("/api/v1/routes/{id}/choice", routeId).with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"waypoints\":" + waypoints + "}"));
    }

    private long plannedRoute() {
        long routeId = newRoute(DEPARTURE);
        routePlanning.plan(routeId);
        return routeId;
    }

    private long newRoute(OffsetDateTime departure) {
        return inTransaction(() -> {
            Trip trip = tripRepository.save(new Trip(userId, "Wellington", DEPARTURE, DEPARTURE.plusDays(7),
                    DEPARTURE.minusDays(2)));
            Stop start = stopRepository.save(new Stop(trip, 0, "Newtown", NEWTOWN[0], NEWTOWN[1], null, departure));
            Stop end = stopRepository.save(new Stop(trip, 1, "Thorndon", THORNDON[0], THORNDON[1], null, null));
            return routeRepository.save(new Route(trip, start, end)).getId();
        });
    }

    /** Longest segment in the middle third of the planned route, away from the stops. */
    private long middleSegment(long routeId) {
        List<RouteSection> sections = routeSectionRepository.findByRouteIdOrderByPosition(routeId);
        return sections.subList(sections.size() / 3, 2 * sections.size() / 3).stream()
                .max(Comparator.comparing(s -> Duration.between(s.getExpectedPassageStart(),
                        s.getExpectedPassageEnd())))
                .orElseThrow().getSegmentId();
    }

    private RequestPostProcessor login(String username) {
        AppUser appUser = appUserProvisioning.findOrCreate(username, null, null);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(username).build();
        return authentication(new AppUserAuthentication(new CurrentUser(appUser.getId(), username), jwt, List.of()));
    }

    private <T> T inTransaction(Supplier<T> action) {
        return transactionTemplate.execute(_ -> action.get());
    }
}