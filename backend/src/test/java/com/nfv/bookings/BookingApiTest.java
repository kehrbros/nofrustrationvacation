package com.nfv.bookings;

import com.jayway.jsonpath.JsonPath;
import com.nfv.assistant.logic.AssistantUnavailableException;
import com.nfv.assistant.logic.ExtractionInput;
import com.nfv.assistant.logic.StructuredExtraction;
import com.nfv.user.entities.AppUser;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Accommodation bookings: time zone of the place, ownership, validation, reading confirmations. */
@SpringBootTest
@AutoConfigureMockMvc
class BookingApiTest {
    private static final String TRIP = "{\"name\":\"Italien\",\"startAt\":\"2027-07-10T00:00:00+02:00\","
            + "\"endAt\":\"2027-07-20T23:59:59+02:00\"}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @MockitoBean
    private StructuredExtraction extraction;

    private RequestPostProcessor user;
    private RequestPostProcessor otherUser;
    private long tripId;
    private long stopId;

    @BeforeEach
    void setUp() throws Exception {
        user = login("bookings-" + UUID.randomUUID());
        otherUser = login("bookings-other-" + UUID.randomUUID());
        tripId = id(mockMvc.perform(post("/api/v1/trips").with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(TRIP)).andExpect(status().isCreated()));
        // adding a stop answers with the trip detail, the new stop is the only one
        String trip = mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rom\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        stopId = ((Number) JsonPath.read(trip, "$.stops[0].id")).longValue();
    }

    @Test
    void checkInIsStoredInTheTimeZoneOfTheAccommodation() throws Exception {
        // Rome in July: +02:00, regardless of the user's zone (fallback New York is ignored with coordinates)
        long bookingId = createBooking(stopId, booking("Hotel Adler", "41.9028", "12.4964", "America/New_York"));

        mockMvc.perform(get("/api/v1/trips/{id}/bookings", tripId).with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(bookingId))
                .andExpect(jsonPath("$[0].stopId").value(stopId))
                .andExpect(jsonPath("$[0].type").value("ACCOMMODATION"))
                .andExpect(jsonPath("$[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$[0].source").value("DOCUMENT"))
                .andExpect(jsonPath("$[0].title").value("Hotel Adler"))
                .andExpect(jsonPath("$[0].startsAt").value("2027-07-12T15:00:00+02:00"))
                .andExpect(jsonPath("$[0].endsAt").value("2027-07-15T10:00:00+02:00"))
                .andExpect(jsonPath("$[0].cancellationDeadline").value("2027-07-10T18:00:00+02:00"))
                .andExpect(jsonPath("$[0].price").value("412.50"))
                .andExpect(jsonPath("$[0].currency").value("EUR"))
                .andExpect(jsonPath("$[0].reference").value("4711"))
                .andExpect(jsonPath("$[0].accommodation.timeZone").value("Europe/Rome"))
                .andExpect(jsonPath("$[0].accommodation.countryCode").value("IT"))
                .andExpect(jsonPath("$[0].accommodation.checkInFrom").value("15:00:00"))
                .andExpect(jsonPath("$[0].accommodation.guests").value(2));
    }

    @Test
    void withoutCoordinatesTheUsersTimeZoneApplies() throws Exception {
        createBooking(stopId, booking("Ferienwohnung", null, null, "America/New_York"));

        mockMvc.perform(get("/api/v1/trips/{id}/bookings", tripId).with(user))
                .andExpect(jsonPath("$[0].startsAt").value("2027-07-12T15:00:00-04:00"))
                .andExpect(jsonPath("$[0].accommodation.timeZone").value("America/New_York"));
    }

    @Test
    void bookingsCanBeChangedAndDeleted() throws Exception {
        long bookingId = createBooking(stopId, booking("Hotel Adler", "41.9028", "12.4964", null));

        mockMvc.perform(put("/api/v1/accommodation-bookings/{id}", bookingId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(booking("Hotel Sole", "41.9028", "12.4964", null).replace("\"4711\"", "\"0815\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Hotel Sole"))
                .andExpect(jsonPath("$.reference").value("0815"))
                .andExpect(jsonPath("$.source").value("DOCUMENT"));

        mockMvc.perform(delete("/api/v1/bookings/{id}", bookingId).with(user).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/trips/{id}/bookings", tripId).with(user))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void bookingsOfOtherUsersAreNotFound() throws Exception {
        long bookingId = createBooking(stopId, booking("Hotel Adler", null, null, "Europe/Berlin"));

        mockMvc.perform(post("/api/v1/stops/{id}/accommodation-bookings", stopId).with(otherUser).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(booking("X", null, null, null)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STOP_NOT_FOUND"));
        mockMvc.perform(delete("/api/v1/bookings/{id}", bookingId).with(otherUser).with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/trips/{id}/bookings", tripId).with(otherUser))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRIP_NOT_FOUND"));
    }

    @Test
    void invalidBookingsAreRejected() throws Exception {
        String valid = booking("Hotel Adler", null, null, "Europe/Berlin");
        for (String invalid : List.of(
                valid.replace("\"checkOut\":\"2027-07-15T10:00:00\"", "\"checkOut\":\"2027-07-11T10:00:00\""),
                valid.replace("\"name\":\"Hotel Adler\"", "\"name\":\" \""),
                valid.replace("\"currency\":\"EUR\"", "\"currency\":null"),
                valid.replace("\"manageUrl\":\"https://secure.booking.com/myreservations.html\"",
                        "\"manageUrl\":\"javascript:alert(1)\""),
                valid.replace("\"fallbackTimeZone\":\"Europe/Berlin\"", "\"fallbackTimeZone\":\"Mars/Olympus\""))) {
            mockMvc.perform(post("/api/v1/stops/{id}/accommodation-bookings", stopId).with(user).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_BOOKING_INPUT"));
        }
    }

    @Test
    void confirmationsAreReadIntoADraft() throws Exception {
        when(extraction.extract(anyLong(), anyString(), eq("accommodation_booking"), any(), any()))
                .thenReturn(JsonMapper.builder().build().readTree("""
                        {"isBooking": true, "name": "Hotel Adler", "addressLine": "Via Roma 1, 00100 Rom",
                         "countryCode": "it", "phone": null, "email": null, "website": "http://adler.example",
                         "provider": "Booking.com", "reference": "4711", "manageUrl": "https://booking.com/x",
                         "checkInDate": "2027-07-12", "checkInTime": "15:00", "checkOutDate": "2027-07-15",
                         "checkOutTime": "11:00", "checkInFrom": "14:00", "checkInUntil": "22:00",
                         "checkOutUntil": "11:00", "guests": 2, "rooms": 1, "price": 412.5, "currency": "EUR",
                         "cancellationDeadline": "10.07.2027", "uncertainFields": ["checkOutTime", "bogus"]}
                        """));

        mockMvc.perform(multipart("/api/v1/bookings/accommodation-drafts")
                        .file(new MockMultipartFile("text", "", "text/plain", "Ihre Buchung ...".getBytes()))
                        .file(new MockMultipartFile("file", "buchung.pdf", "application/pdf", new byte[]{1, 2, 3}))
                        .with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.found").value(true))
                .andExpect(jsonPath("$.name").value("Hotel Adler"))
                .andExpect(jsonPath("$.countryCode").value("IT"))
                .andExpect(jsonPath("$.website").doesNotExist())
                .andExpect(jsonPath("$.checkInDate").value("2027-07-12"))
                .andExpect(jsonPath("$.checkInTime").value("15:00:00"))
                .andExpect(jsonPath("$.price").value("412.50"))
                .andExpect(jsonPath("$.cancellationDeadline").doesNotExist())
                .andExpect(jsonPath("$.uncertainFields", contains("checkOutTime", "cancellationDeadline")));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExtractionInput>> inputs = ArgumentCaptor.forClass(List.class);
        verify(extraction).extract(anyLong(), anyString(), anyString(), any(), inputs.capture());
        assertThat(inputs.getValue()).hasSize(2);
        assertThat(inputs.getValue().get(0)).isEqualTo(new ExtractionInput.Text("Ihre Buchung ..."));
        assertThat(inputs.getValue().get(1)).isInstanceOf(ExtractionInput.Pdf.class);
    }

    @Test
    void readingFailsCleanly() throws Exception {
        mockMvc.perform(multipart("/api/v1/bookings/accommodation-drafts")
                        .file(new MockMultipartFile("file", "x.docx", "application/msword", new byte[]{1}))
                        .with(user).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BOOKING_INPUT"));

        when(extraction.extract(anyLong(), anyString(), anyString(), any(), any()))
                .thenThrow(new AssistantUnavailableException("assistant is not configured"));
        mockMvc.perform(multipart("/api/v1/bookings/accommodation-drafts")
                        .file(new MockMultipartFile("text", "", "text/plain", "Buchung".getBytes()))
                        .with(user).with(csrf()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ASSISTANT_UNAVAILABLE"));
    }

    private long createBooking(long stop, String json) throws Exception {
        return id(mockMvc.perform(post("/api/v1/stops/{id}/accommodation-bookings", stop).with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json)).andExpect(status().isCreated()));
    }

    private static String booking(String name, String latitude, String longitude, String fallbackTimeZone) {
        return "{\"source\":\"DOCUMENT\",\"name\":\"" + name + "\",\"addressLine\":\"Via Roma 1\",\"countryCode\":\"it\","
                + "\"latitude\":" + latitude + ",\"longitude\":" + longitude + ","
                + "\"provider\":\"Booking.com\",\"reference\":\"4711\","
                + "\"manageUrl\":\"https://secure.booking.com/myreservations.html\","
                + "\"checkIn\":\"2027-07-12T15:00:00\",\"checkOut\":\"2027-07-15T10:00:00\","
                + "\"fallbackTimeZone\":" + (fallbackTimeZone == null ? "null" : "\"" + fallbackTimeZone + "\"") + ","
                + "\"checkInFrom\":\"15:00\",\"checkOutUntil\":\"10:00\",\"guests\":2,\"rooms\":1,"
                + "\"price\":\"412.50\",\"currency\":\"EUR\",\"cancellationDeadline\":\"2027-07-10T18:00:00\"}";
    }

    private static long id(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private RequestPostProcessor login(String username) {
        AppUser appUser = appUserProvisioning.findOrCreate(username, null, null);
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(username).build();
        return authentication(new AppUserAuthentication(new CurrentUser(appUser.getId(), username), jwt, List.of()));
    }
}
