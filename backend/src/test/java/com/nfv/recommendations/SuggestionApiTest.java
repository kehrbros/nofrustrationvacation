package com.nfv.recommendations;

import com.jayway.jsonpath.JsonPath;
import com.nfv.incidents.data.ConsequenceRepository;
import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.logic.IncidentLogic;
import com.nfv.network.logic.RegionImport;
import com.nfv.network.logic.RegionStatus;
import com.nfv.recommendations.data.SuggestionSetRepository;
import com.nfv.recommendations.entities.SuggestionStatus;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Suggestions API: suggest, read, apply, stale sets, foreign routes, see Docs/recommendations.md#api. */
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
    private SuggestionSetRepository setRepository;
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
    void suggestionsAreComputedOnceAndCanBeRead() throws Exception {
        long routeId = plannedRoute();

        String body = suggest(routeId, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CURRENT"))
                .andExpect(jsonPath("$.waypointMode").value("IGNORE"))
                .andExpect(jsonPath("$.options[0].rank").value(1))
                .andExpect(jsonPath("$.options[0].labels[0]").value("FASTEST"))
                .andExpect(jsonPath("$.options[0].geometry[0].latitude").isNumber())
                .andExpect(jsonPath("$.reference.arrivalAt").isString())
                .andReturn().getResponse().getContentAsString();
        int setId = JsonPath.read(body, "$.id");

        suggest(routeId, null).andExpect(jsonPath("$.id").value(setId));
        mockMvc.perform(get("/api/v1/suggestion-sets/{id}", setId).with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value(routeId))
                .andExpect(jsonPath("$.options").isNotEmpty());
    }

    @Test
    void missingDepartureIsAProblemNotAnError() throws Exception {
        long routeId = newRoute(null);

        suggest(routeId, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.problem").value("MISSING_DEPARTURE"));
    }

    @Test
    void invalidCountIsRejected() throws Exception {
        suggest(plannedRoute(), "{\"count\":9}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SUGGESTION_INPUT"));
    }

    @Test
    void foreignRoutesAndSetsAreNotFound() throws Exception {
        long routeId = plannedRoute();
        int setId = JsonPath.read(suggest(routeId, "{}").andReturn().getResponse().getContentAsString(), "$.id");
        int optionId = JsonPath.read(mockMvc.perform(get("/api/v1/suggestion-sets/{id}", setId).with(user))
                .andReturn().getResponse().getContentAsString(), "$.options[0].id");

        mockMvc.perform(post("/api/v1/routes/{id}/suggestions", routeId).with(otherUser).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/suggestion-sets/{id}", setId).with(otherUser))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        apply(otherUser, setId, optionId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
    }

    /** IGNORE: the waypoint of the user is replaced by the via points of the suggestion, the route planned again. */
    @Test
    void applyReplacesWaypointsAndPlansTheRoute() throws Exception {
        long routeId = newRoute(DEPARTURE);
        long kelburn = inTransaction(() -> waypointRepository.save(new Waypoint(routeRepository.findById(routeId)
                .orElseThrow(), 0, KELBURN[0], KELBURN[1], "Kelburn", null, WaypointOrigin.USER)).getId());
        routePlanning.plan(routeId);
        String body = suggest(routeId, "{}").andReturn().getResponse().getContentAsString();
        int setId = JsonPath.read(body, "$.id");
        int optionId = JsonPath.read(body, "$.options[-1].id");
        List<?> viaPoints = JsonPath.read(body, "$.options[-1].viaPoints");

        apply(user, setId, optionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value(routeId))
                .andExpect(jsonPath("$.planOutcome").value("PLANNED"));

        List<Waypoint> waypoints = waypointRepository.findByRouteIdOrderByPosition(routeId);
        assertThat(waypoints).extracting(Waypoint::getId).doesNotContain(kelburn);
        assertThat(waypoints).hasSize(viaPoints.size())
                .allSatisfy(w -> assertThat(w.getOrigin()).isEqualTo(WaypointOrigin.SUGGESTION));
        assertThat(setRepository.findById((long) setId).orElseThrow().getStatus())
                .isEqualTo(SuggestionStatus.APPLIED);
        apply(user, setId, optionId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUGGESTION_STALE"));
    }

    @Test
    void applyOnStaleSetIsRejected() throws Exception {
        long routeId = plannedRoute();
        String body = suggest(routeId, "{}").andReturn().getResponse().getContentAsString();
        int setId = JsonPath.read(body, "$.id");
        int optionId = JsonPath.read(body, "$.options[0].id");

        routePlanning.plan(routeId);

        apply(user, setId, optionId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SUGGESTION_STALE"));
    }

    /** A suggestion around a closure: after applying, the consequence of the closure is gone. */
    @Test
    void applyAroundClosureRemovesTheConsequence() throws Exception {
        long routeId = plannedRoute();
        long closed = middleSegment(routeId);
        inTransaction(() -> incidentLogic.create(IncidentType.CLOSURE, "closed", DEPARTURE.minusHours(1),
                DEPARTURE.plusHours(3), null, Set.of(closed)));
        assertThat(consequenceRepository.findByRouteId(routeId)).isNotEmpty();
        String body = suggest(routeId, "{}")
                .andExpect(jsonPath("$.reference.blocked").value(true))
                .andReturn().getResponse().getContentAsString();
        int setId = JsonPath.read(body, "$.id");
        int optionId = JsonPath.read(body, "$.options[0].id");

        apply(user, setId, optionId).andExpect(jsonPath("$.planOutcome").value("PLANNED"));

        assertThat(routeSectionRepository.findByRouteIdOrderByPosition(routeId))
                .extracting(RouteSection::getSegmentId).doesNotContain(closed);
        assertThat(consequenceRepository.findByRouteId(routeId)).isEmpty();
    }

    private org.springframework.test.web.servlet.ResultActions suggest(long routeId, String json) throws Exception {
        var request = post("/api/v1/routes/{id}/suggestions", routeId).with(user).with(csrf());
        return mockMvc.perform(json == null ? request
                : request.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private org.springframework.test.web.servlet.ResultActions apply(RequestPostProcessor who, int setId,
                                                                     int optionId) throws Exception {
        return mockMvc.perform(post("/api/v1/suggestion-sets/{setId}/options/{optionId}/apply", setId, optionId)
                .with(who).with(csrf()));
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