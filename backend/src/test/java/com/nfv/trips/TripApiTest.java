package com.nfv.trips;

import com.jayway.jsonpath.JsonPath;
import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.logic.IncidentLogic;
import com.nfv.network.data.ExtractVersionRepository;
import com.nfv.network.data.RoadSegmentRepository;
import com.nfv.network.entities.ExtractVersion;
import com.nfv.network.entities.RoadSegment;
import com.nfv.trips.data.RouteSectionRepository;
import com.nfv.trips.logic.PlannedSection;
import com.nfv.trips.logic.RouteLogic;
import com.nfv.user.entities.AppUser;
import com.nfv.user.logic.AppUserProvisioning;
import com.nfv.user.security.AppUserAuthentication;
import com.nfv.user.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Trips API: period with offset, list phases, stops with routes (insert, move, remove), waypoints, plan status, title
 * photo, incidents per trip. Routing itself is covered by RoutePlanningTest; the stops here are outside the test
 * network, so planning ends in NO_ROUTE or ROUTING_UNAVAILABLE.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TripApiTest {
    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private IncidentLogic incidentLogic;
    @Autowired
    private RouteLogic routeLogic;
    @Autowired
    private ExtractVersionRepository extractVersionRepository;
    @Autowired
    private RoadSegmentRepository roadSegmentRepository;
    @Autowired
    private RouteSectionRepository routeSectionRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private RequestPostProcessor user;
    private RequestPostProcessor otherUser;

    @BeforeEach
    void setUp() {
        user = login("trips-" + UUID.randomUUID());
        otherUser = login("trips-other-" + UUID.randomUUID());
    }

    @Test
    void periodKeepsTheOffsetItWasEnteredIn() throws Exception {
        long tripId = createTrip(user, "Toskana", "2027-07-12T00:00:00+02:00", "2027-07-26T23:59:00+02:00");

        mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Toskana"))
                .andExpect(jsonPath("$.startAt").value("2027-07-12T00:00:00+02:00"))
                .andExpect(jsonPath("$.endAt").value("2027-07-26T23:59:00+02:00"))
                .andExpect(jsonPath("$.phase").value("UPCOMING"));
    }

    @Test
    void travelersAreKeptChangedAndChecked() throws Exception {
        long tripId = createTrip(user, "Toskana", "2027-07-12T00:00:00+02:00", "2027-07-26T23:59:00+02:00");
        mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andExpect(jsonPath("$.travelers").value(is((Object) null)));

        String json = "{\"name\":\"Toskana\",\"startAt\":\"2027-07-12T00:00:00+02:00\","
                + "\"endAt\":\"2027-07-26T23:59:00+02:00\",\"travelers\":%s}";
        mockMvc.perform(put("/api/v1/trips/{id}", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.formatted("4")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.travelers").value(4));
        mockMvc.perform(put("/api/v1/trips/{id}", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.formatted("0")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andExpect(jsonPath("$.travelers").value(4));
    }

    @Test
    void listIsAscendingByStartWithPhases() throws Exception {
        createTrip(user, "Bald", iso(NOW.plusDays(10)), iso(NOW.plusDays(12)));
        createTrip(user, "Vorbei", iso(NOW.minusDays(20)), iso(NOW.minusDays(18)));
        createTrip(user, "Jetzt", iso(NOW.minusDays(1)), iso(NOW.plusDays(1)));
        createTrip(otherUser, "Fremd", iso(NOW), iso(NOW.plusDays(1)));

        mockMvc.perform(get("/api/v1/trips").with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", contains("Vorbei", "Jetzt", "Bald")))
                .andExpect(jsonPath("$[*].phase", contains("PAST", "CURRENT", "UPCOMING")));
    }

    @Test
    void invalidTripsAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/trips").with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(tripJson("Toskana", "2027-07-26T00:00:00+02:00", "2027-07-12T00:00:00+02:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TRIP_INPUT"));
        mockMvc.perform(post("/api/v1/trips").with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tripsOfOtherUsersAreNotFound() throws Exception {
        long tripId = createTrip(user, "Toskana", iso(NOW), iso(NOW.plusDays(3)));

        mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(otherUser))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(otherUser).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rom\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/trips/{id}/incidents", tripId).with(otherUser))
                .andExpect(status().isNotFound());
    }

    @Test
    void stopsAreAppendedAndConnectedByRoutes() throws Exception {
        long tripId = createTrip(user, "Toskana", "2027-07-12T00:00:00+02:00", "2027-07-26T23:59:00+02:00");

        long munich = addStop(tripId, "{\"name\":\"München\",\"latitude\":48.14,\"longitude\":11.58,"
                + "\"departureAt\":\"2027-07-12T08:00:00+02:00\"}");
        long verona = addStop(tripId, "{\"name\":\"Verona\",\"latitude\":45.44,\"longitude\":10.99,"
                + "\"arrivalAt\":\"2027-07-12T14:30:00+02:00\"}");

        mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andExpect(jsonPath("$.stops[*].name", contains("München", "Verona")))
                .andExpect(jsonPath("$.stops[0].departureAt").value("2027-07-12T08:00:00+02:00"))
                .andExpect(jsonPath("$.stops[1].arrivalAt").value("2027-07-12T14:30:00+02:00"))
                .andExpect(jsonPath("$.routes", hasSize(1)))
                .andExpect(jsonPath("$.routes[0].fromStopId").value(munich))
                .andExpect(jsonPath("$.routes[0].toStopId").value(verona));

        mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rom\",\"latitude\":41.9}"))
                .andExpect(status().isBadRequest());
    }

    /** Inserting, moving and removing stops keeps routes of unchanged neighbours and creates the missing ones. */
    @Test
    void stopsAreInsertedMovedAndRemovedWithRoutes() throws Exception {
        long tripId = createTrip(user, "Toskana", "2027-07-12T00:00:00+02:00", "2027-07-26T23:59:00+02:00");
        long a = addStop(tripId, "{\"name\":\"A\"}");
        long b = addStop(tripId, "{\"name\":\"B\"}");
        long c = addStop(tripId, "{\"name\":\"C\"}");
        long bc = routes(tripId).get(List.of(b, c));

        long d = addStop(tripId, "{\"name\":\"D\",\"position\":1}");
        mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andExpect(jsonPath("$.stops[*].name", contains("A", "D", "B", "C")))
                .andExpect(jsonPath("$.stops[*].position", contains(0, 1, 2, 3)))
                .andExpect(jsonPath("$.routes[*].planOutcome", contains(
                        "MISSING_COORDINATES", "MISSING_COORDINATES", "MISSING_COORDINATES")));
        assertThat(routes(tripId)).containsOnlyKeys(List.of(a, d), List.of(d, b), List.of(b, c))
                .containsEntry(List.of(b, c), bc);
        long ad = routes(tripId).get(List.of(a, d));

        mockMvc.perform(put("/api/v1/trips/{id}/stops/{stopId}/position", tripId, c).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"position\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[*].name", contains("C", "A", "D", "B")));
        assertThat(routes(tripId)).containsOnlyKeys(List.of(c, a), List.of(a, d), List.of(d, b))
                .containsEntry(List.of(a, d), ad);

        mockMvc.perform(delete("/api/v1/trips/{id}/stops/{stopId}", tripId, d).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[*].name", contains("C", "A", "B")))
                .andExpect(jsonPath("$.stops[*].position", contains(0, 1, 2)));
        assertThat(routes(tripId)).containsOnlyKeys(List.of(c, a), List.of(a, b));

        mockMvc.perform(put("/api/v1/trips/{id}/stops/{stopId}/position", tripId, c).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"position\":3}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"E\",\"position\":5}"))
                .andExpect(status().isBadRequest());
    }

    /** New coordinates discard the sections (and their consequences), a new arrival does not. */
    @Test
    void changedCoordinatesDiscardTheSections() throws Exception {
        OffsetDateTime departure = NOW.plusDays(2);
        long tripId = createTrip(user, "Toskana", iso(departure), iso(departure.plusDays(5)));
        addStop(tripId, "{\"name\":\"Innsbruck\",\"latitude\":47.26,\"longitude\":11.39,\"departureAt\":\""
                + iso(departure) + "\"}");
        long verona = addStop(tripId, "{\"name\":\"Verona\",\"latitude\":45.44,\"longitude\":10.99}");
        long routeId = routes(tripId).values().iterator().next();
        long segmentId = segment(departure.minusDays(1));
        routeLogic.replaceSections(routeId, List.of(new PlannedSection(segmentId, departure, departure.plusHours(1))));

        mockMvc.perform(put("/api/v1/trips/{id}/stops/{stopId}", tripId, verona).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Verona\",\"latitude\":45.44,"
                                + "\"longitude\":10.99,\"arrivalAt\":\"" + iso(departure.plusHours(4)) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].planOutcome").value("PLANNED"));
        assertThat(routeSectionRepository.findByRouteIdOrderByPosition(routeId)).hasSize(1);

        mockMvc.perform(put("/api/v1/trips/{id}/stops/{stopId}", tripId, verona).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Verona\",\"latitude\":45.40,"
                                + "\"longitude\":10.95,\"arrivalAt\":\"" + iso(departure.plusHours(4)) + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routes[0].plannedAt").doesNotExist())
                .andExpect(jsonPath("$.routes[0].planOutcome").value(anyOf(is("NO_ROUTE"), is("ROUTING_UNAVAILABLE"))));
        assertThat(routeSectionRepository.findByRouteIdOrderByPosition(routeId)).isEmpty();
    }

    @Test
    void waypointsAreReplacedInOrder() throws Exception {
        long tripId = createTrip(user, "Toskana", iso(NOW), iso(NOW.plusDays(3)));
        addStop(tripId, "{\"name\":\"Innsbruck\"}");
        addStop(tripId, "{\"name\":\"Verona\"}");
        long routeId = routes(tripId).values().iterator().next();

        String body = mockMvc.perform(put("/api/v1/trips/{id}/routes/{routeId}/waypoints", tripId, routeId)
                        .with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"latitude\":47.0,\"longitude\":11.5,\"name\":\"Brenner\"},"
                                + "{\"latitude\":46.5,\"longitude\":11.35,\"name\":\"Bozen\",\"roadHint\":\"A22\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waypoints[*].name", contains("Brenner", "Bozen")))
                .andExpect(jsonPath("$.route.planOutcome").value("MISSING_COORDINATES"))
                .andReturn().getResponse().getContentAsString();
        long brenner = ((Number) JsonPath.read(body, "$.waypoints[0].id")).longValue();
        long bozen = ((Number) JsonPath.read(body, "$.waypoints[1].id")).longValue();

        mockMvc.perform(put("/api/v1/trips/{id}/routes/{routeId}/waypoints", tripId, routeId)
                        .with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":" + bozen + ",\"latitude\":46.5,\"longitude\":11.35,\"name\":\"Bolzano\"},"
                                + "{\"latitude\":46.07,\"longitude\":11.12,\"name\":\"Trient\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waypoints[*].name", contains("Bolzano", "Trient")))
                .andExpect(jsonPath("$.waypoints[0].id").value(bozen))
                .andExpect(jsonPath("$.waypoints[0].roadHint").doesNotExist());

        mockMvc.perform(put("/api/v1/trips/{id}/routes/{routeId}/waypoints", tripId, routeId)
                        .with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":" + brenner + ",\"latitude\":47.0,\"longitude\":11.5}]"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WAYPOINT_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/trips/{id}/routes/{routeId}", tripId, routeId).with(otherUser))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
        long otherTrip = createTrip(user, "Anders", iso(NOW), iso(NOW.plusDays(3)));
        mockMvc.perform(get("/api/v1/trips/{id}/routes/{routeId}", otherTrip, routeId).with(user))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/trips/{id}/routes/{routeId}/plan", tripId, routeId).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.planOutcome").value("MISSING_COORDINATES"));
    }

    @Test
    void tripIsUpdatedAndDeletedWithEverythingOnIt() throws Exception {
        OffsetDateTime departure = NOW.plusDays(2);
        long tripId = createTrip(user, "Toskana", iso(departure), iso(departure.plusDays(5)));
        long innsbruck = addStop(tripId, "{\"name\":\"Innsbruck\",\"departureAt\":\"" + iso(departure) + "\"}");
        addStop(tripId, "{\"name\":\"Verona\"}");
        long routeId = routes(tripId).values().iterator().next();
        mockMvc.perform(put("/api/v1/trips/{id}/routes/{routeId}/waypoints", tripId, routeId)
                        .with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"latitude\":47.0,\"longitude\":11.5}]"))
                .andExpect(status().isOk());
        routeLogic.replaceSections(routeId, List.of(new PlannedSection(segment(departure.minusDays(1)),
                departure, departure.plusHours(1))));

        mockMvc.perform(put("/api/v1/trips/{id}", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tripJson("Italien", "2027-08-01T00:00:00+02:00", "2027-08-10T23:59:00+02:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Italien"))
                .andExpect(jsonPath("$.startAt").value("2027-08-01T00:00:00+02:00"));
        mockMvc.perform(put("/api/v1/trips/{id}/stops/{stopId}", tripId, innsbruck).with(otherUser).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Innsbruck\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/trips/{id}", tripId).with(otherUser).with(csrf()))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/trips/{id}", tripId).with(user).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user)).andExpect(status().isNotFound());
        assertThat(routeSectionRepository.findByRouteIdOrderByPosition(routeId)).isEmpty();
    }

    @Test
    void stopsOfAnotherTripAreNotFound() throws Exception {
        long tripId = createTrip(user, "Toskana", iso(NOW), iso(NOW.plusDays(3)));
        long otherTrip = createTrip(user, "Anders", iso(NOW), iso(NOW.plusDays(3)));
        long stop = addStop(otherTrip, "{\"name\":\"Rom\"}");

        mockMvc.perform(put("/api/v1/trips/{id}/stops/{stopId}", tripId, stop).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rom\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOP_NOT_FOUND"));
        mockMvc.perform(delete("/api/v1/trips/{id}/stops/{stopId}", tripId, stop).with(user).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOP_NOT_FOUND"));
    }

    @Test
    void titlePhotoIsUploadedAndServed() throws Exception {
        long tripId = createTrip(user, "Toskana", iso(NOW), iso(NOW.plusDays(3)));
        mockMvc.perform(get("/api/v1/trips/{id}/cover-image", tripId).with(user)).andExpect(status().isNotFound());

        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2};
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/trips/{id}/cover-image", tripId)
                        .file(new MockMultipartFile("file", "cover.jpg", "image/jpeg", jpeg)).with(user).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/trips/{id}/cover-image", tripId)
                        .file(new MockMultipartFile("file", "cover.gif", "image/gif", jpeg)).with(user).with(csrf()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/trips/{id}/cover-image", tripId).with(user))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(content().bytes(jpeg));
        mockMvc.perform(get("/api/v1/trips").with(user)).andExpect(jsonPath("$[0].hasCoverImage").value(true));
        mockMvc.perform(get("/api/v1/trips/{id}/cover-image", tripId).with(otherUser)).andExpect(status().isNotFound());
    }

    @Test
    void incidentsOnTheRoutesAreListedPerTrip() throws Exception {
        OffsetDateTime departure = NOW.plusDays(2);
        long tripId = createTrip(user, "Toskana", iso(departure), iso(departure.plusDays(5)));
        addStop(tripId, "{\"name\":\"Innsbruck\",\"departureAt\":\"" + iso(departure) + "\"}");
        addStop(tripId, "{\"name\":\"Verona\"}");
        String detail = mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andReturn().getResponse().getContentAsString();
        long routeId = ((Number) JsonPath.read(detail, "$.routes[0].id")).longValue();

        long segmentId = segment(departure.minusDays(1));
        routeLogic.replaceSections(routeId, List.of(new PlannedSection(segmentId, departure, departure.plusHours(1))));
        transactionTemplate.executeWithoutResult(_ -> incidentLogic.create(IncidentType.CLOSURE, "Vollsperrung A22",
                departure.minusHours(1), departure.plusHours(4), null, Set.of(segmentId)));

        mockMvc.perform(get("/api/v1/trips/{id}/incidents", tripId).with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].routeId").value(routeId))
                .andExpect(jsonPath("$[0].type").value("CLOSURE"))
                .andExpect(jsonPath("$[0].impact").value("BLOCKED"))
                .andExpect(jsonPath("$[0].description").value("Vollsperrung A22"))
                // closed until 4 h after departure: wait 4 h, Verona has no arrival → DELAYED, never TOO_LATE
                .andExpect(jsonPath("$[0].category").value("DELAYED"))
                .andExpect(jsonPath("$[0].match").value("DIRECT"))
                .andExpect(jsonPath("$[0].assessedDelayMinutes").value(240))
                .andExpect(jsonPath("$[0].delayAssumed").value(false))
                .andExpect(jsonPath("$[0].expectedArrival").isNotEmpty())
                .andExpect(jsonPath("$[0].deadline").doesNotExist());
        mockMvc.perform(get("/api/v1/trips/{id}/incidents/routes", tripId).with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].routeId").value(routeId))
                .andExpect(jsonPath("$[0].worstCategory").value("DELAYED"))
                .andExpect(jsonPath("$[0].unpassable").value(false))
                .andExpect(jsonPath("$[0].slackMinutes").doesNotExist());
        mockMvc.perform(get("/api/v1/trips/{id}/incidents/routes", tripId).with(otherUser))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/incidents/trip-counts").with(user))
                .andExpect(jsonPath("$[0].tripId").value(tripId))
                .andExpect(jsonPath("$[0].count").value(1));
    }

    private long createTrip(RequestPostProcessor who, String name, String startAt, String endAt) throws Exception {
        String body = mockMvc.perform(post("/api/v1/trips").with(who).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(tripJson(name, startAt, endAt)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    /** Adds the stop, returns its id (the highest of the trip, the response is the whole trip). */
    private long addStop(long tripId, String json) throws Exception {
        String body = mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        List<Number> ids = JsonPath.read(body, "$.stops[*].id");
        return ids.stream().mapToLong(Number::longValue).max().orElseThrow();
    }

    /** Routes of the trip as {@code fromStopId → toStopId → routeId}, in the order of the stops. */
    private Map<List<Long>, Long> routes(long tripId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> routes = JsonPath.read(body, "$.routes");
        Map<List<Long>, Long> byStops = new HashMap<>();
        for (Map<String, Object> route : routes) {
            byStops.put(List.of(((Number) route.get("fromStopId")).longValue(),
                    ((Number) route.get("toStopId")).longValue()), ((Number) route.get("id")).longValue());
        }
        return byStops;
    }

    private long segment(OffsetDateTime since) {
        ExtractVersion version = extractVersionRepository.save(new ExtractVersion("test", since));
        LineString geometry = new GeometryFactory(new PrecisionModel(), 4326).createLineString(new Coordinate[]{
                new Coordinate(11.4, 47.2), new Coordinate(11.5, 47.0)});
        return roadSegmentRepository.save(new RoadSegment("test",
                ThreadLocalRandom.current().nextLong(1, Integer.MAX_VALUE),
                472_000_000, 114_000_000, 470_000_000, 115_000_000, 0,
                "A13", "Brenner", "MOTORWAY", false, false, 25_000, geometry, version)).getId();
    }

    private static String tripJson(String name, String startAt, String endAt) {
        return "{\"name\":\"" + name + "\",\"startAt\":\"" + startAt + "\",\"endAt\":\"" + endAt + "\"}";
    }

    private static String iso(OffsetDateTime time) {
        return time.toString();
    }

    private RequestPostProcessor login(String username) {
        AppUser appUser = appUserProvisioning.findOrCreate(username, null, null);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(username).build();
        return authentication(new AppUserAuthentication(new CurrentUser(appUser.getId(), username), jwt, List.of()));
    }
}
