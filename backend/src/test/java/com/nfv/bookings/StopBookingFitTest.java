package com.nfv.bookings;

import com.jayway.jsonpath.JsonPath;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bookings lie within the days of their stop: changing the stop or the booking checks it, extends the stop or shortens
 * the booking (Docs/domain.md#bookings).
 */
@SpringBootTest
@AutoConfigureMockMvc
class StopBookingFitTest {
    private static final String TRIP = "{\"name\":\"Nordinsel\",\"startAt\":\"2027-07-10T00:00:00+12:00\","
            + "\"endAt\":\"2027-07-20T23:59:59+12:00\"}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserProvisioning appUserProvisioning;

    private RequestPostProcessor user;
    private long tripId;
    private long stopId;
    private long bookingId;

    @BeforeEach
    void setUp() throws Exception {
        String username = "stop-fit-" + UUID.randomUUID();
        AppUser appUser = appUserProvisioning.findOrCreate(username, null, null);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(username).build();
        user = authentication(new AppUserAuthentication(new CurrentUser(appUser.getId(), username), jwt, List.of()));
        tripId = id(mockMvc.perform(post("/api/v1/trips").with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(TRIP)).andExpect(status().isCreated()));
        String trip = mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stop("Wellington", "2027-07-12T12:00:00+12:00", "2027-07-15T11:00:00+12:00")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        stopId = ((Number) JsonPath.read(trip, "$.stops[0].id")).longValue();
        // in Wellington, check-in 15:00 and check-out 10:00 local (+12:00 in July)
        bookingId = id(mockMvc.perform(post("/api/v1/stops/{id}/accommodation-bookings", stopId).with(user)
                .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(booking("2027-07-12", "2027-07-15"))).andExpect(status().isCreated()));
    }

    @Test
    void changesWithinTheDaysOfTheBookingsPass() throws Exception {
        // other times on the same days, a new name: the booking still fits
        updateStop("Te Aro", "2027-07-12T18:00:00+12:00", "2027-07-15T08:00:00+12:00", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[0].name").value("Te Aro"));
        // a longer stay always fits
        updateStop("Te Aro", "2027-07-11T18:00:00+12:00", "2027-07-16T08:00:00+12:00", "")
                .andExpect(status().isOk());
    }

    @Test
    void shorterStopIsRefusedWithTheBookingsAndTheExtendedDays() throws Exception {
        updateStop("Wellington", "2027-07-13T12:00:00+12:00", "2027-07-14T11:00:00+12:00", "")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOP_BOOKINGS_CONFLICT"))
                .andExpect(jsonPath("$.bookings[0].bookingId").value(bookingId))
                .andExpect(jsonPath("$.bookings[0].title").value("Hotel Waterloo"))
                .andExpect(jsonPath("$.bookings[0].trimmable").value(true))
                .andExpect(jsonPath("$.extendedArrivalAt").value("2027-07-12T12:00:00+12:00"))
                .andExpect(jsonPath("$.extendedDepartureAt").value("2027-07-15T11:00:00+12:00"));

        // nothing changed
        mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andExpect(jsonPath("$.stops[0].arrivalAt").value("2027-07-12T12:00:00+12:00"));
    }

    @Test
    void bookingsCanBeShortenedToTheNewDays() throws Exception {
        updateStop("Wellington", "2027-07-13T12:00:00+12:00", "2027-07-14T11:00:00+12:00", "?bookingConflict=TRIM_BOOKINGS")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stops[0].arrivalAt").value("2027-07-13T12:00:00+12:00"));

        // same times of day at the place, only the days moved
        mockMvc.perform(get("/api/v1/trips/{id}/bookings", tripId).with(user))
                .andExpect(jsonPath("$[0].startsAt").value("2027-07-13T15:00:00+12:00"))
                .andExpect(jsonPath("$[0].endsAt").value("2027-07-14T10:00:00+12:00"));
    }

    @Test
    void bookingsWithoutANightLeftCannotBeShortened() throws Exception {
        updateStop("Wellington", "2027-07-16T12:00:00+12:00", "2027-07-17T11:00:00+12:00", "?bookingConflict=TRIM_BOOKINGS")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STOP_BOOKINGS_CONFLICT"))
                .andExpect(jsonPath("$.bookings[0].trimmable").value(false))
                .andExpect(jsonPath("$.extendedArrivalAt").value("2027-07-12T12:00:00+12:00"))
                .andExpect(jsonPath("$.extendedDepartureAt").doesNotExist());
    }

    @Test
    void bookingOutsideTheStopIsRefused() throws Exception {
        mockMvc.perform(changeBooking(booking("2027-07-11", "2027-07-15"), ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_OUTSIDE_STOP"))
                .andExpect(jsonPath("$.stopArrivalAt").value("2027-07-12T12:00:00+12:00"))
                .andExpect(jsonPath("$.extendedArrivalAt").value("2027-07-11T12:00:00+12:00"))
                .andExpect(jsonPath("$.extendedDepartureAt").doesNotExist())
                .andExpect(jsonPath("$.trimmable").value(true));
    }

    @Test
    void stopCanBeExtendedToTheBooking() throws Exception {
        mockMvc.perform(changeBooking(booking("2027-07-11", "2027-07-16"), "?stopConflict=EXTEND_STOP"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startsAt").value("2027-07-11T15:00:00+12:00"));

        mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andExpect(jsonPath("$.stops[0].arrivalAt").value("2027-07-11T12:00:00+12:00"))
                .andExpect(jsonPath("$.stops[0].departureAt").value("2027-07-16T11:00:00+12:00"));
    }

    @Test
    void bookingCanBeShortenedToTheStop() throws Exception {
        mockMvc.perform(changeBooking(booking("2027-07-11", "2027-07-16"), "?stopConflict=TRIM_BOOKING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startsAt").value("2027-07-12T15:00:00+12:00"))
                .andExpect(jsonPath("$.endsAt").value("2027-07-15T10:00:00+12:00"));
    }

    private ResultActions updateStop(String name, String arrival, String departure, String query) throws Exception {
        return mockMvc.perform(put("/api/v1/trips/{tripId}/stops/{stopId}" + query, tripId, stopId).with(user)
                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(stop(name, arrival, departure)));
    }

    private org.springframework.test.web.servlet.RequestBuilder changeBooking(String json, String query) {
        return put("/api/v1/accommodation-bookings/{id}" + query, bookingId).with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    /** In Wellington, stops need coordinates in New Zealand. */
    private static String stop(String name, String arrival, String departure) {
        return "{\"name\":\"" + name + "\",\"latitude\":-41.2865,\"longitude\":174.7762,\"arrivalAt\":\"" + arrival
                + "\",\"departureAt\":\"" + departure + "\"}";
    }

    private static String booking(String checkIn, String checkOut) {
        return "{\"source\":\"MANUAL\",\"name\":\"Hotel Waterloo\",\"latitude\":-41.2790,\"longitude\":174.7806,"
                + "\"checkIn\":\"" + checkIn + "T15:00:00\",\"checkOut\":\"" + checkOut + "T10:00:00\"}";
    }

    private static long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
