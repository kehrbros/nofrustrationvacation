package com.vacantagent.trips;

import com.jayway.jsonpath.JsonPath;
import com.vacantagent.incidents.entities.IncidentType;
import com.vacantagent.incidents.logic.IncidentLogic;
import com.vacantagent.network.data.RoadRepository;
import com.vacantagent.network.data.SegmentRepository;
import com.vacantagent.network.entities.Road;
import com.vacantagent.network.entities.Segment;
import com.vacantagent.trips.logic.PlannedSection;
import com.vacantagent.trips.logic.RouteLogic;
import com.vacantagent.user.entities.AppUser;
import com.vacantagent.user.logic.AppUserProvisioning;
import com.vacantagent.user.security.AppUserAuthentication;
import com.vacantagent.user.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Trips API: period with offset, list phases, stops with routes, title photo, incidents per trip. */
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
    private RoadRepository roadRepository;
    @Autowired
    private SegmentRepository segmentRepository;
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

        Road road = roadRepository.save(new Road("A" + UUID.randomUUID().toString().substring(0, 8), "Brenner"));
        long segmentId = segmentRepository.save(new Segment(road, "Innsbruck", "Brenner",
                "{\"type\":\"LineString\",\"coordinates\":[[11.4,47.2],[11.5,47.0]]}")).getId();
        routeLogic.replaceSections(routeId, List.of(new PlannedSection(segmentId, departure, departure.plusHours(1))));
        transactionTemplate.executeWithoutResult(_ -> incidentLogic.create(IncidentType.CLOSURE, "Vollsperrung A22",
                departure.minusHours(1), departure.plusHours(4), null, Set.of(segmentId)));

        mockMvc.perform(get("/api/v1/trips/{id}/incidents", tripId).with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].routeId").value(routeId))
                .andExpect(jsonPath("$[0].type").value("CLOSURE"))
                .andExpect(jsonPath("$[0].impact").value("BLOCKED"))
                .andExpect(jsonPath("$[0].description").value("Vollsperrung A22"));
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

    private long addStop(long tripId, String json) throws Exception {
        String body = mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
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
