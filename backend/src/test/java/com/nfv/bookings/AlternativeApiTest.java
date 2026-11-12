package com.nfv.bookings;

import com.jayway.jsonpath.JsonPath;
import com.nfv.assistant.logic.ExtractionInput;
import com.nfv.assistant.logic.ReasoningEffort;
import com.nfv.assistant.logic.StructuredExtraction;
import com.nfv.bookings.logic.LiteApi;
import com.nfv.bookings.logic.OfferUnavailableException;
import com.nfv.paypal.PayPalAmount;
import com.nfv.paypal.PayPalCheckout;
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
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Alternatives to an accommodation booking: criteria from the booking, hard check of the offers, ranking, booking
 * with PayPal authorization; LiteAPI, PayPal and the assistant are mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AlternativeApiTest {
    private static final String TRIP = "{\"name\":\"Italien\",\"startAt\":\"2027-07-10T00:00:00+02:00\","
            + "\"endAt\":\"2027-07-20T23:59:59+02:00\"}";
    private static final String BOOKING = "{\"source\":\"DOCUMENT\",\"name\":\"Hotel Adler\",\"addressLine\":\"Via Roma 1\","
            + "\"latitude\":41.9028,\"longitude\":12.4964,\"checkIn\":\"2027-07-12T15:00:00\","
            + "\"checkOut\":\"2027-07-15T10:00:00\",\"guests\":2,\"rooms\":1,\"price\":\"412.50\",\"currency\":\"EUR\"}";
    private static final String CRITERIA = "{\"latitude\":41.9028,\"longitude\":12.4964,\"radiusMeters\":5000,"
            + "\"checkIn\":\"2027-07-12\",\"checkOut\":\"2027-07-15\",\"guests\":2,\"rooms\":1,\"maxPrice\":\"412.50\","
            + "\"currency\":\"EUR\"}";
    private static final double[] WELLINGTON = {-41.2865, 174.7762};
    private static final double[] PORIRUA = {-41.1339, 174.8406};

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @MockitoBean
    private LiteApi liteApi;
    @MockitoBean
    private PayPalCheckout checkout;
    @MockitoBean
    private StructuredExtraction extraction;
    @Autowired
    private com.nfv.bookings.logic.AlternativeReservations reservations;
    @Autowired
    private com.nfv.bookings.data.BookingReservationRepository reservationRepository;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private RequestPostProcessor user;
    private long tripId;
    private long bookingId;
    private long stopId;

    @BeforeEach
    void setUp() throws Exception {
        user = login("alternatives-" + UUID.randomUUID());
        tripId = id(mockMvc.perform(post("/api/v1/trips").with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(TRIP)).andExpect(status().isCreated()));
        // stops only in New Zealand; the Roman hotels stay, the stop does not follow them (outside the regions)
        String trip = mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(stop("Rom", WELLINGTON)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        stopId = ((Number) JsonPath.read(trip, "$.stops[0].id")).longValue();
        bookingId = id(mockMvc.perform(post("/api/v1/stops/{id}/accommodation-bookings", stopId).with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BOOKING)).andExpect(status().isCreated()));
    }

    @Test
    void criteriaAreFilledFromTheBooking() throws Exception {
        mockMvc.perform(get("/api/v1/accommodation-bookings/{id}/alternative-criteria", bookingId).with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(41.9028))
                .andExpect(jsonPath("$.placeName").value("Via Roma 1"))
                .andExpect(jsonPath("$.radiusMeters").value(5000))
                .andExpect(jsonPath("$.checkIn").value("2027-07-12"))
                .andExpect(jsonPath("$.checkOut").value("2027-07-15"))
                .andExpect(jsonPath("$.earliestCheckIn").isNotEmpty())
                .andExpect(jsonPath("$.guests").value(2))
                .andExpect(jsonPath("$.rooms").value(1))
                .andExpect(jsonPath("$.maxPrice").value("412.50"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.minStars").isEmpty())
                .andExpect(jsonPath("$.missing", hasSize(0)));
    }

    @Test
    void onlyOffersMeetingAllCriteriaAreRankedByTheAssistant() throws Exception {
        when(liteApi.rates(any())).thenReturn(List.of(
                offer("cheap", 41.9030, 12.4970, "150.00", "EUR"),
                offer("cheap", 41.9030, 12.4970, "180.00", "EUR"),
                offer("good", 41.9050, 12.5000, "300.00", "EUR"),
                // 400 net + 10 % fee = 440 > 412.50
                offer("expensive", 41.9030, 12.4970, "400.00", "EUR"),
                // about 11 km away
                offer("far", 42.0000, 12.4964, "100.00", "EUR"),
                offer("dollar", 41.9030, 12.4970, "100.00", "USD")));
        when(extraction.isAvailable()).thenReturn(true);
        when(extraction.extract(anyLong(), anyString(), eq("alternative_ranking"), any(), any(),
                eq(ReasoningEffort.LOW)))
                .thenReturn(jsonMapper.readTree("""
                        {"ranking": [{"hotelId": "good", "comparable": true, "reason": "Gleiche Kategorie, ruhig gelegen."},
                                     {"hotelId": "invented", "comparable": true, "reason": "x"},
                                     {"hotelId": "cheap", "comparable": true, "reason": "Günstig, gleich nebenan."}]}"""));

        mockMvc.perform(post("/api/v1/accommodation-bookings/{id}/alternative-searches", bookingId).with(user)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CRITERIA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[*].hotelId", contains("good", "cheap")))
                .andExpect(jsonPath("$.candidates[0].price").value("330.00"))
                .andExpect(jsonPath("$.candidates[0].reason").value("Gleiche Kategorie, ruhig gelegen."))
                .andExpect(jsonPath("$.candidates[1].price").value("165.00"))
                .andExpect(jsonPath("$.candidates[1].possiblySame").value(false))
                .andExpect(jsonPath("$.recommendedHotelId").value("good"))
                .andExpect(jsonPath("$.ranked").value(true))
                .andExpect(jsonPath("$.offersChecked").value(6))
                .andExpect(jsonPath("$.excludedByAssistant").value(0));
    }

    @Test
    void assistantLeavesOutHotelsNotComparableToTheCurrentOne() throws Exception {
        when(liteApi.rates(any())).thenReturn(List.of(offer("hotel", 41.9050, 12.5000, "300.00", "EUR"),
                offer("hostel", 41.9030, 12.4970, "90.00", "EUR")));
        when(liteApi.profile("hostel")).thenReturn(new LiteApi.HotelProfile("hostel", null, "Hostel",
                "Dormitories with shared bathrooms.", List.of("Shared kitchen")));
        when(extraction.isAvailable()).thenReturn(true);
        when(extraction.extract(anyLong(), anyString(), eq("alternative_ranking"), any(), any(),
                eq(ReasoningEffort.LOW)))
                .thenReturn(jsonMapper.readTree("""
                        {"ranking": [{"hotelId": "hotel", "comparable": true, "reason": "Gleiche Kategorie."},
                                     {"hotelId": "hostel", "comparable": false, "reason": "Hostel mit Mehrbettzimmern."}]}"""));

        mockMvc.perform(post("/api/v1/accommodation-bookings/{id}/alternative-searches", bookingId).with(user)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CRITERIA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[*].hotelId", contains("hotel")))
                .andExpect(jsonPath("$.recommendedHotelId").value("hotel"))
                .andExpect(jsonPath("$.ranked").value(true))
                .andExpect(jsonPath("$.excludedByAssistant").value(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExtractionInput>> input = ArgumentCaptor.forClass(List.class);
        verify(extraction).extract(anyLong(), anyString(), eq("alternative_ranking"), any(), input.capture(),
                eq(ReasoningEffort.LOW));
        String text = ((ExtractionInput.Text) input.getValue().getFirst()).text();
        assertThat(text).contains("\"kind\":\"Hostel\"", "Dormitories with shared bathrooms.", "\"pricePerNight\":\"137.50 EUR\"");
    }

    @Test
    void minimumStarsLeaveOutHotelsWithFewerOrNoStars() throws Exception {
        when(liteApi.rates(any())).thenReturn(List.of(offer("four", 41.9050, 12.5000, "300.00", "EUR", 4.0),
                offer("three", 41.9030, 12.4970, "150.00", "EUR", 3.0),
                offer("none", 41.9030, 12.4970, "100.00", "EUR", null)));

        mockMvc.perform(post("/api/v1/accommodation-bookings/{id}/alternative-searches", bookingId).with(user)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(CRITERIA.replace("}", ",\"minStars\":4}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[*].hotelId", contains("four")));

        ArgumentCaptor<LiteApi.RateQuery> query = ArgumentCaptor.forClass(LiteApi.RateQuery.class);
        verify(liteApi).rates(query.capture());
        assertThat(query.getValue().minStars()).isEqualTo(4);
    }

    @Test
    void minimumStarsOutOfRangeAreRejected() throws Exception {
        mockMvc.perform(post("/api/v1/accommodation-bookings/{id}/alternative-searches", bookingId).with(user)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(CRITERIA.replace("}", ",\"minStars\":6}")))
                .andExpect(status().isBadRequest());
        verify(liteApi, never()).rates(any());
    }

    @Test
    void withoutAssistantCandidatesAreOrderedByPrice() throws Exception {
        when(liteApi.rates(any())).thenReturn(List.of(offer("good", 41.9050, 12.5000, "300.00", "EUR"),
                offer("cheap", 41.9030, 12.4970, "150.00", "EUR")));

        mockMvc.perform(post("/api/v1/accommodation-bookings/{id}/alternative-searches", bookingId).with(user)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CRITERIA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.candidates[*].hotelId", contains("cheap", "good")))
                .andExpect(jsonPath("$.ranked").value(false));
        verify(extraction, never()).extract(anyLong(), anyString(), anyString(), any(), any(), any(ReasoningEffort.class));
    }

    @Test
    void reservationIsAuthorizedBookedCapturedAndAddedToTheStop() throws Exception {
        prebookAndHotel("150.00");
        when(checkout.createOrder(anyString(), any(), anyString(), eq(PayPalCheckout.Intent.AUTHORIZE), any(), any()))
                .thenReturn(new PayPalCheckout.CheckoutOrder("O-1", "https://www.sandbox.paypal.com/checkoutnow?token=O-1"));

        String reserved = mockMvc.perform(post("/api/v1/accommodation-bookings/{id}/alternative-reservations", bookingId)
                        .with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(reservation()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.price").value("165.00"))
                .andExpect(jsonPath("$.fee").value("15.00"))
                .andExpect(jsonPath("$.approveUrl").value("https://www.sandbox.paypal.com/checkoutnow?token=O-1"))
                .andReturn().getResponse().getContentAsString();
        long reservationId = ((Number) JsonPath.read(reserved, "$.id")).longValue();
        ArgumentCaptor<URI> returnUrl = ArgumentCaptor.forClass(URI.class);
        verify(checkout).createOrder(eq("stay-" + reservationId), eq(new PayPalAmount("EUR", new BigDecimal("165.00"))),
                anyString(), eq(PayPalCheckout.Intent.AUTHORIZE), returnUrl.capture(), any());
        assertThat(returnUrl.getValue().toString()).endsWith("/reisen/" + tripId + "/buchungen/" + bookingId
                + "/alternativen?reservierung=" + reservationId);

        when(checkout.authorize("O-1", "authorize-stay-" + reservationId)).thenReturn(
                new PayPalCheckout.Payment("A-1", "CREATED", new PayPalAmount("EUR", new BigDecimal("165.00"))));
        when(liteApi.book(eq("P-1"), any(), eq(1), eq("nfv-stay-" + reservationId)))
                .thenReturn(new LiteApi.ProviderBooking("LB-1", "HC-77", "CONFIRMED"));
        when(checkout.captureAuthorization("A-1", "capture-stay-" + reservationId))
                .thenReturn(new PayPalCheckout.Payment("C-1", "COMPLETED", null));

        mockMvc.perform(post("/api/v1/booking-reservations/{id}/booking", reservationId).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.booking.source").value("SEARCH"))
                .andExpect(jsonPath("$.booking.title").value("Hotel Sole"))
                .andExpect(jsonPath("$.booking.reference").value("HC-77"))
                .andExpect(jsonPath("$.booking.price").value("165.00"))
                .andExpect(jsonPath("$.booking.startsAt").value("2027-07-12T14:00:00+02:00"))
                .andExpect(jsonPath("$.booking.cancellationDeadline").value("2027-07-11T12:00:00+02:00"))
                .andExpect(jsonPath("$.booking.accommodation.placeRef").value("liteapi:hotel/sole"));
        // repeating does not book again
        mockMvc.perform(post("/api/v1/booking-reservations/{id}/booking", reservationId).with(user).with(csrf()))
                .andExpect(jsonPath("$.status").value("BOOKED"));
        verify(liteApi).book(anyString(), any(), anyInt(), anyString());
        // the replaced booking is on the to-do list, to cancel at its provider
        mockMvc.perform(get("/api/v1/trips/{id}/booking-todos", tripId).with(user))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].kind").value("CANCEL"))
                .andExpect(jsonPath("$[0].reason").value("REPLACED"))
                .andExpect(jsonPath("$[0].bookingId").value(bookingId))
                .andExpect(jsonPath("$[0].title").value("Hotel Adler"))
                .andExpect(jsonPath("$[0].bookedInApp").value(false))
                .andExpect(jsonPath("$[0].doneAt").doesNotExist());

        mockMvc.perform(post("/api/v1/bookings/{id}/cancellation", bookingId).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/v1/trips/{id}/booking-todos", tripId).with(user))
                .andExpect(jsonPath("$[0].doneAt").exists());
        mockMvc.perform(get("/api/v1/trips/{id}/bookings", tripId).with(user))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void reservationAtAStopWithoutBookingCreatesTheBookingThere() throws Exception {
        prebookAndHotel("150.00");
        when(checkout.createOrder(anyString(), any(), anyString(), eq(PayPalCheckout.Intent.AUTHORIZE), any(), any()))
                .thenReturn(new PayPalCheckout.CheckoutOrder("O-9", "https://paypal.test/O-9"));
        long otherStop = ((Number) JsonPath.read(mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(stop("Florenz", PORIRUA)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.stops[1].id"))
                .longValue();

        String reserved = mockMvc.perform(post("/api/v1/stops/{id}/stay-reservations", otherStop).with(user)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(reservation()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"))
                .andExpect(jsonPath("$.stopId").value(otherStop))
                .andExpect(jsonPath("$.replacesBookingId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long reservationId = ((Number) JsonPath.read(reserved, "$.id")).longValue();
        ArgumentCaptor<URI> returnUrl = ArgumentCaptor.forClass(URI.class);
        verify(checkout).createOrder(anyString(), any(), anyString(), any(), returnUrl.capture(), any());
        assertThat(returnUrl.getValue().toString()).endsWith("/reisen/" + tripId + "/stops/" + otherStop
                + "/unterkunft?reservierung=" + reservationId);

        mockPaymentAndBooking(reservationId, "O-9");
        mockMvc.perform(post("/api/v1/booking-reservations/{id}/booking", reservationId).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.booking.stopId").value(otherStop))
                .andExpect(jsonPath("$.booking.notes").value(org.hamcrest.Matchers.startsWith(
                        "In der App gebucht, bezahlt mit PayPal.")));
        mockMvc.perform(get("/api/v1/trips/{id}/booking-todos", tripId).with(user))
                .andExpect(jsonPath("$", hasSize(0)));
        // foreign stop
        mockMvc.perform(post("/api/v1/stops/{id}/stay-reservations", otherStop).with(login("other-" + UUID.randomUUID()))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(reservation()))
                .andExpect(status().isNotFound());
    }

    @Test
    void hotelInAnotherPlaceMovesTheStopAndPlansItsRoutesAgain() throws Exception {

        // trip Wellington -> Porirua, the booking in Porirua gets replaced by a hotel in Paraparaumu, about 28 km away
        String wellington = mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Wellington\",\"latitude\":"
                                + WELLINGTON[0] + ",\"longitude\":" + WELLINGTON[1]
                                + ",\"departureAt\":\"2027-07-12T09:00:00+12:00\",\"position\":0}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long poriruaId = ((Number) JsonPath.read(wellington, "$.stops[1].id")).longValue();
        mockMvc.perform(put("/api/v1/trips/{id}/stops/{stopId}", tripId, poriruaId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(stop("Porirua", PORIRUA)))
                .andExpect(status().isOk());
        long routeId = ((Number) JsonPath.read(mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andReturn().getResponse().getContentAsString(), "$.routes[0].id")).longValue();
        prebookAndHotel("150.00");
        when(liteApi.hotel("sole")).thenReturn(new LiteApi.HotelDetails("sole", "Hotel Sole", "Kapiti Road 5",
                "Paraparaumu", "NZ", -40.9147, 175.0058, "+64 4 123", null, null, LocalTime.of(14, 0),
                LocalTime.of(22, 0), LocalTime.of(11, 0)));
        when(checkout.createOrder(anyString(), any(), anyString(), eq(PayPalCheckout.Intent.AUTHORIZE), any(), any()))
                .thenReturn(new PayPalCheckout.CheckoutOrder("O-1", "https://www.sandbox.paypal.com/checkoutnow?token=O-1"));
        long reservationId = id(mockMvc.perform(post("/api/v1/accommodation-bookings/{id}/alternative-reservations",
                        bookingId).with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(reservation()))
                .andExpect(status().isCreated()));

        mockMvc.perform(get("/api/v1/booking-reservations/{id}/route-check", reservationId).with(user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.otherPlace").value(true))
                .andExpect(jsonPath("$.distanceMeters").value(org.hamcrest.Matchers.greaterThan(20_000)))
                .andExpect(jsonPath("$.routeId").value(routeId))
                .andExpect(jsonPath("$.checkInUntil").value("2027-07-12T22:00:00+12:00"));

        mockPaymentAndBooking(reservationId, "O-1");
        mockMvc.perform(post("/api/v1/booking-reservations/{id}/booking", reservationId).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.replannedRouteIds", contains((int) routeId)));
        mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andExpect(jsonPath("$.stops[1].name").value("Paraparaumu"))
                .andExpect(jsonPath("$.stops[1].latitude").value(-40.9147))
                .andExpect(jsonPath("$.stops[1].longitude").value(175.0058))
                .andExpect(jsonPath("$.stops[0].name").value("Wellington"));
    }

    @Test
    void hotelInTheSamePlaceLeavesTheStop() throws Exception {
        long stopId = ((Number) JsonPath.read(mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andReturn().getResponse().getContentAsString(), "$.stops[0].id")).longValue();
        mockMvc.perform(put("/api/v1/trips/{id}/stops/{stopId}", tripId, stopId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(stop("Wellington Zentrum", WELLINGTON)))
                .andExpect(status().isOk());
        prebookAndHotel("150.00");
        when(liteApi.hotel("sole")).thenReturn(new LiteApi.HotelDetails("sole", "Hotel Sole", "Lambton Quay 5",
                "Wellington", "NZ", -41.2880, 174.7770, "+64 4 123", null, null, LocalTime.of(14, 0), null,
                LocalTime.of(11, 0)));
        when(checkout.createOrder(anyString(), any(), anyString(), eq(PayPalCheckout.Intent.AUTHORIZE), any(), any()))
                .thenReturn(new PayPalCheckout.CheckoutOrder("O-1", "https://www.sandbox.paypal.com/checkoutnow?token=O-1"));
        long reservationId = id(mockMvc.perform(post("/api/v1/accommodation-bookings/{id}/alternative-reservations",
                        bookingId).with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(reservation()))
                .andExpect(status().isCreated()));

        mockMvc.perform(get("/api/v1/booking-reservations/{id}/route-check", reservationId).with(user))
                .andExpect(jsonPath("$.otherPlace").value(false))
                .andExpect(jsonPath("$.arrivalAt").doesNotExist());
        mockPaymentAndBooking(reservationId, "O-1");
        mockMvc.perform(post("/api/v1/booking-reservations/{id}/booking", reservationId).with(user).with(csrf()))
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.replannedRouteIds", hasSize(0)));
        mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andExpect(jsonPath("$.stops[0].name").value("Wellington Zentrum"))
                .andExpect(jsonPath("$.stops[0].latitude").value(WELLINGTON[0]));
    }

    @Test
    void refusedBookingReleasesThePayment() throws Exception {
        prebookAndHotel("150.00");
        when(checkout.createOrder(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new PayPalCheckout.CheckoutOrder("O-2", "https://paypal.test/O-2"));
        long reservationId = ((Number) JsonPath.read(mockMvc.perform(
                        post("/api/v1/accommodation-bookings/{id}/alternative-reservations", bookingId).with(user)
                                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(reservation()))
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        when(checkout.authorize(eq("O-2"), anyString())).thenReturn(
                new PayPalCheckout.Payment("A-2", "CREATED", new PayPalAmount("EUR", new BigDecimal("165.00"))));
        when(liteApi.book(anyString(), any(), anyInt(), anyString()))
                .thenThrow(new OfferUnavailableException("4002", "rate not found"));

        mockMvc.perform(post("/api/v1/booking-reservations/{id}/booking", reservationId).with(user).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OFFER_UNAVAILABLE"));

        verify(checkout).voidAuthorization("A-2", "void-stay-" + reservationId);
        verify(checkout, never()).captureAuthorization(anyString(), anyString());
        mockMvc.perform(get("/api/v1/booking-reservations/{id}", reservationId).with(user))
                .andExpect(jsonPath("$.status").value("FAILED"));
        mockMvc.perform(get("/api/v1/trips/{id}/bookings", tripId).with(user))
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void priceAboveTheMaximumIsNotPaid() throws Exception {
        // 400 net + 10 % = 440 > 412.50
        prebookAndHotel("400.00");

        mockMvc.perform(post("/api/v1/accommodation-bookings/{id}/alternative-reservations", bookingId).with(user)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(reservation()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PRICE_EXCEEDED"));
        verify(checkout, never()).createOrder(anyString(), any(), anyString(), any(), any(), any());
    }

    @Test
    void invalidGuestDataNamesTheField() throws Exception {
        mockMvc.perform(post("/api/v1/accommodation-bookings/{id}/alternative-reservations", bookingId).with(user)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(reservation().replace("+49 170 1234567", "call me")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_BOOKING_INPUT"))
                .andExpect(jsonPath("$.field").value("phone"));
        mockMvc.perform(post("/api/v1/accommodation-bookings/{id}/alternative-reservations", bookingId).with(user)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(reservation().replace("\"Erika\"", "\" \"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("firstName"));
        verify(liteApi, never()).prebook(anyString());
    }

    /** LiteAPI offer ids are longer than 1000 characters, the plan checkout refused them as "check your details". */
    @Test
    void longOfferIdsAreAccepted() throws Exception {
        prebookAndHotel("150.00");
        String offerId = "OF-" + "x".repeat(3000);
        when(liteApi.prebook(offerId)).thenReturn(new LiteApi.Prebook("P-1", new BigDecimal("150.00"), "EUR", null,
                false, true, LocalDateTime.of(2027, 7, 11, 12, 0)));
        when(checkout.createOrder(anyString(), any(), anyString(), eq(PayPalCheckout.Intent.AUTHORIZE), any(), any()))
                .thenReturn(new PayPalCheckout.CheckoutOrder("O-1", "https://www.sandbox.paypal.com/checkoutnow?token=O-1"));

        mockMvc.perform(post("/api/v1/accommodation-bookings/{id}/alternative-reservations", bookingId).with(user)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(reservation().replace("\"OF-1\"", "\"" + offerId + "\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));
    }

    @Test
    void alternativesOfOtherUsersAreNotFound() throws Exception {
        RequestPostProcessor other = login("alternatives-other-" + UUID.randomUUID());
        mockMvc.perform(get("/api/v1/accommodation-bookings/{id}/alternative-criteria", bookingId).with(other))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/accommodation-bookings/{id}/alternative-searches", bookingId).with(other)
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(CRITERIA))
                .andExpect(status().isNotFound());
    }

    @Test
    void approvedPaymentIsBookedInTheBackgroundWhenTheUserDoesNotComeBack() throws Exception {
        long reservationId = reserve("O-3");
        mockMvc.perform(get("/api/v1/trips/{id}/booking-reservations", tripId).with(user))
                .andExpect(jsonPath("$[*].id", contains((int) reservationId)))
                .andExpect(jsonPath("$[0].payPalOrderId").value("O-3"));
        when(checkout.getOrder("O-3")).thenReturn(new PayPalCheckout.OrderState("O-3", "APPROVED", null, null, null));
        mockPaymentAndBooking(reservationId, "O-3");

        reservations.recover(reservationId);

        mockMvc.perform(get("/api/v1/booking-reservations/{id}", reservationId).with(user))
                .andExpect(jsonPath("$.status").value("BOOKED"));
        mockMvc.perform(get("/api/v1/trips/{id}/booking-reservations", tripId).with(user))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void paymentNotApprovedStaysOpenUntilTheTimeout() throws Exception {
        long reservationId = reserve("O-4");
        when(checkout.getOrder("O-4")).thenReturn(new PayPalCheckout.OrderState("O-4", "PAYER_ACTION_REQUIRED", null,
                null, null));

        reservations.recover(reservationId);

        mockMvc.perform(get("/api/v1/booking-reservations/{id}", reservationId).with(user))
                .andExpect(jsonPath("$.status").value("PAYMENT_PENDING"));
        verify(checkout, never()).authorize(anyString(), anyString());
    }

    @Test
    void lostBookingAnswerContinuesWithTheEarlierBooking() throws Exception {
        long reservationId = reserve("O-5");
        when(checkout.authorize(eq("O-5"), anyString())).thenReturn(
                new PayPalCheckout.Payment("A-5", "CREATED", new PayPalAmount("EUR", new BigDecimal("165.00"))));
        when(liteApi.book(anyString(), any(), anyInt(), anyString()))
                .thenThrow(new OfferUnavailableException("4005", "duplicate clientReference"));
        when(liteApi.findBooking("nfv-stay-" + reservationId))
                .thenReturn(java.util.Optional.of(new LiteApi.ProviderBooking("LB-5", "HC-5", "CONFIRMED")));
        when(checkout.captureAuthorization(eq("A-5"), anyString())).thenReturn(
                new PayPalCheckout.Payment("C-5", "COMPLETED", null));

        mockMvc.perform(post("/api/v1/booking-reservations/{id}/booking", reservationId).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.booking.reference").value("HC-5"));
        verify(checkout, never()).voidAuthorization(anyString(), anyString());
    }

    @Test
    void refusedCaptureNeedsAttentionInsteadOfAnUnpaidBooking() throws Exception {
        long reservationId = reserve("O-6");
        when(checkout.authorize(eq("O-6"), anyString())).thenReturn(
                new PayPalCheckout.Payment("A-6", "CREATED", new PayPalAmount("EUR", new BigDecimal("165.00"))));
        when(liteApi.book(anyString(), any(), anyInt(), anyString()))
                .thenReturn(new LiteApi.ProviderBooking("LB-6", null, "CONFIRMED"));
        when(checkout.captureAuthorization(eq("A-6"), anyString()))
                .thenThrow(new com.nfv.paypal.PayPalRejectedException(422, "AUTHORIZATION_EXPIRED", "d-1", "expired"));

        mockMvc.perform(post("/api/v1/booking-reservations/{id}/booking", reservationId).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_ATTENTION"));
        mockMvc.perform(get("/api/v1/trips/{id}/bookings", tripId).with(user))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(get("/api/v1/trips/{id}/booking-reservations", tripId).with(user))
                .andExpect(jsonPath("$[0].status").value("NEEDS_ATTENTION"));
    }

    @Test
    void cancellingABookingMadeInTheAppRefundsThePayment() throws Exception {
        long reservationId = reserve("O-7");
        mockPaymentAndBooking(reservationId, "O-7");
        String booked = mockMvc.perform(post("/api/v1/booking-reservations/{id}/booking", reservationId).with(user)
                .with(csrf())).andReturn().getResponse().getContentAsString();
        long newBookingId = ((Number) JsonPath.read(booked, "$.bookingId")).longValue();
        when(liteApi.cancel("LB-1")).thenReturn(new LiteApi.Cancellation("CANCELLED", null));
        when(checkout.refundCapture("C-1", null, "refund-stay-" + reservationId))
                .thenReturn(new PayPalCheckout.Payment("R-1", "COMPLETED", null));

        mockMvc.perform(post("/api/v1/bookings/{id}/refund-cancellation", newBookingId).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refundAmount").value("165.00"))
                .andExpect(jsonPath("$.refunded").value(true))
                .andExpect(jsonPath("$.booking.status").value("CANCELLED"));
        // the original booking was not made in the app
        mockMvc.perform(post("/api/v1/bookings/{id}/refund-cancellation", bookingId).with(user).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCELLATION_NOT_POSSIBLE"));
    }

    @Test
    void failedRefundIsRetriedInTheBackground() throws Exception {
        long reservationId = reserve("O-8");
        mockPaymentAndBooking(reservationId, "O-8");
        String booked = mockMvc.perform(post("/api/v1/booking-reservations/{id}/booking", reservationId).with(user)
                .with(csrf())).andReturn().getResponse().getContentAsString();
        long newBookingId = ((Number) JsonPath.read(booked, "$.bookingId")).longValue();
        when(liteApi.cancel("LB-1")).thenReturn(new LiteApi.Cancellation("CANCELLED", null));
        when(checkout.refundCapture(anyString(), any(), anyString()))
                .thenThrow(new com.nfv.paypal.PayPalUnavailableException("down"));

        mockMvc.perform(post("/api/v1/bookings/{id}/refund-cancellation", newBookingId).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.refunded").value(false));

        org.mockito.Mockito.reset(checkout);
        when(checkout.refundCapture("C-1", null, "refund-stay-" + reservationId))
                .thenReturn(new PayPalCheckout.Payment("R-8", "COMPLETED", null));
        reservations.recover(reservationId);
        mockMvc.perform(get("/api/v1/booking-reservations/{id}", reservationId).with(user))
                .andExpect(jsonPath("$.refunded").value(true));
    }

    @Test
    void bookingMadeInTheAppRemovedWithoutCancellingCanBeCancelledFromTheList() throws Exception {
        long reservationId = reserve("O-9");
        mockPaymentAndBooking(reservationId, "O-9");
        String booked = mockMvc.perform(post("/api/v1/booking-reservations/{id}/booking", reservationId).with(user)
                .with(csrf())).andReturn().getResponse().getContentAsString();
        long newBookingId = ((Number) JsonPath.read(booked, "$.bookingId")).longValue();

        mockMvc.perform(delete("/api/v1/bookings/{id}", newBookingId).with(user).with(csrf()))
                .andExpect(status().isNoContent());
        String todos = mockMvc.perform(get("/api/v1/trips/{id}/booking-todos", tripId).with(user))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].reason").value("BOOKING_REMOVED"))
                .andExpect(jsonPath("$[0].title").value("Hotel Sole"))
                .andExpect(jsonPath("$[0].bookingId").doesNotExist())
                .andExpect(jsonPath("$[0].bookedInApp").value(true))
                .andExpect(jsonPath("$[0].cancellableInApp").value(true))
                // the replaced booking was not made in the app
                .andExpect(jsonPath("$[1].reason").value("REPLACED"))
                .andExpect(jsonPath("$[1].cancellableInApp").value(false))
                .andReturn().getResponse().getContentAsString();
        long todoId = ((Number) JsonPath.read(todos, "$[0].id")).longValue();
        long replacedTodoId = ((Number) JsonPath.read(todos, "$[1].id")).longValue();

        when(liteApi.cancel("LB-1")).thenReturn(new LiteApi.Cancellation("CANCELLED", null));
        when(checkout.refundCapture("C-1", null, "refund-stay-" + reservationId))
                .thenReturn(new PayPalCheckout.Payment("R-9", "COMPLETED", null));
        mockMvc.perform(post("/api/v1/booking-todos/{id}/refund-cancellation", todoId).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refundAmount").value("165.00"))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.refunded").value(true))
                .andExpect(jsonPath("$.todo.doneAt").exists())
                .andExpect(jsonPath("$.todo.cancellableInApp").value(false));
        // once only
        mockMvc.perform(post("/api/v1/booking-todos/{id}/refund-cancellation", todoId).with(user).with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANCELLATION_NOT_POSSIBLE"));
        mockMvc.perform(post("/api/v1/booking-todos/{id}/refund-cancellation", replacedTodoId).with(user).with(csrf()))
                .andExpect(status().isConflict());
        verify(liteApi).cancel("LB-1");
    }

    @Test
    void paidReservationStaysWhenItsStopIsDeletedSoTheListCanCancelIt() throws Exception {
        long reservationId = reserve("O-10");
        mockPaymentAndBooking(reservationId, "O-10");
        mockMvc.perform(post("/api/v1/booking-reservations/{id}/booking", reservationId).with(user).with(csrf()))
                .andExpect(jsonPath("$.status").value("BOOKED"));

        mockMvc.perform(delete("/api/v1/trips/{id}/stops/{stopId}", tripId, stopId).with(user).with(csrf()))
                .andExpect(status().is2xxSuccessful());
        String todos = mockMvc.perform(get("/api/v1/trips/{id}/booking-todos", tripId).with(user))
                .andExpect(jsonPath("$[?(@.reason == 'STOP_REMOVED')]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.reason == 'STOP_REMOVED')].cancellableInApp").value(true))
                .andReturn().getResponse().getContentAsString();
        long todoId = ((Number) JsonPath.<List<Object>>read(todos, "$[?(@.reason == 'STOP_REMOVED')].id").getFirst())
                .longValue();
        // without its stop the reservation is not shown any more
        mockMvc.perform(get("/api/v1/booking-reservations/{id}", reservationId).with(user))
                .andExpect(status().isNotFound());

        when(liteApi.cancel("LB-1")).thenReturn(new LiteApi.Cancellation("CANCELLED", null));
        when(checkout.refundCapture("C-1", null, "refund-stay-" + reservationId))
                .thenReturn(new PayPalCheckout.Payment("R-10", "COMPLETED", null));
        mockMvc.perform(post("/api/v1/booking-todos/{id}/refund-cancellation", todoId).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refunded").value(true))
                .andExpect(jsonPath("$.todo.doneAt").exists());
        // another user cannot cancel it
        mockMvc.perform(post("/api/v1/booking-todos/{id}/refund-cancellation", todoId)
                        .with(login("other-" + UUID.randomUUID())).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingTheTripDeletesItsReservationsWithoutCancelling() throws Exception {
        // a paid booking at a stop deleted earlier, which only its to-do still knows
        long paidId = reserve("O-11");
        mockPaymentAndBooking(paidId, "O-11");
        mockMvc.perform(post("/api/v1/booking-reservations/{id}/booking", paidId).with(user).with(csrf()))
                .andExpect(jsonPath("$.status").value("BOOKED"));
        mockMvc.perform(delete("/api/v1/trips/{id}/stops/{stopId}", tripId, stopId).with(user).with(csrf()))
                .andExpect(status().is2xxSuccessful());
        // and one at a new stop that needs attention (booked, capture refused)
        String trip = mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(stop("Rom", WELLINGTON)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        stopId = ((Number) JsonPath.read(trip, "$.stops[0].id")).longValue();
        bookingId = id(mockMvc.perform(post("/api/v1/stops/{id}/accommodation-bookings", stopId).with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BOOKING)).andExpect(status().isCreated()));
        long stuckId = reserve("O-12");
        when(checkout.authorize(eq("O-12"), anyString())).thenReturn(
                new PayPalCheckout.Payment("A-12", "CREATED", new PayPalAmount("EUR", new BigDecimal("165.00"))));
        when(liteApi.book(anyString(), any(), anyInt(), anyString()))
                .thenReturn(new LiteApi.ProviderBooking("LB-12", null, "CONFIRMED"));
        when(checkout.captureAuthorization(eq("A-12"), anyString()))
                .thenThrow(new com.nfv.paypal.PayPalRejectedException(422, "AUTHORIZATION_EXPIRED", "d-1", "expired"));
        mockMvc.perform(post("/api/v1/booking-reservations/{id}/booking", stuckId).with(user).with(csrf()))
                .andExpect(jsonPath("$.status").value("NEEDS_ATTENTION"));

        mockMvc.perform(delete("/api/v1/trips/{id}", tripId).with(user).with(csrf()))
                .andExpect(status().is2xxSuccessful());
        mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user)).andExpect(status().isNotFound());
        assertThat(reservationRepository.findById(paidId)).isEmpty();
        assertThat(reservationRepository.findById(stuckId)).isEmpty();
        verify(liteApi, org.mockito.Mockito.never()).cancel(anyString());
    }

    private long reserve(String orderId) throws Exception {
        prebookAndHotel("150.00");
        when(checkout.createOrder(anyString(), any(), anyString(), any(), any(), any()))
                .thenReturn(new PayPalCheckout.CheckoutOrder(orderId, "https://paypal.test/" + orderId));
        return ((Number) JsonPath.read(mockMvc.perform(
                        post("/api/v1/accommodation-bookings/{id}/alternative-reservations", bookingId).with(user)
                                .with(csrf()).contentType(MediaType.APPLICATION_JSON).content(reservation()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void mockPaymentAndBooking(long reservationId, String orderId) {
        when(checkout.authorize(orderId, "authorize-stay-" + reservationId)).thenReturn(
                new PayPalCheckout.Payment("A-1", "CREATED", new PayPalAmount("EUR", new BigDecimal("165.00"))));
        when(liteApi.book(eq("P-1"), any(), eq(1), eq("nfv-stay-" + reservationId)))
                .thenReturn(new LiteApi.ProviderBooking("LB-1", "HC-77", "CONFIRMED"));
        when(checkout.captureAuthorization("A-1", "capture-stay-" + reservationId))
                .thenReturn(new PayPalCheckout.Payment("C-1", "COMPLETED", null));
    }

    private void prebookAndHotel(String net) {
        when(liteApi.prebook("OF-1")).thenReturn(new LiteApi.Prebook("P-1", new BigDecimal(net), "EUR", null, false,
                true, LocalDateTime.of(2027, 7, 11, 12, 0)));
        when(liteApi.hotel("sole")).thenReturn(new LiteApi.HotelDetails("sole", "Hotel Sole", "Via Nazionale 5", "Rom",
                "IT", 41.9010, 12.4950, "+39 06 123", null, null, LocalTime.of(14, 0), null, LocalTime.of(11, 0)));
    }

    private static String stop(String name, double[] point) {
        return "{\"name\":\"" + name + "\",\"latitude\":" + point[0] + ",\"longitude\":" + point[1] + "}";
    }

    private static String reservation() {
        return "{\"hotelId\":\"sole\",\"offerId\":\"OF-1\",\"checkIn\":\"2027-07-12\",\"checkOut\":\"2027-07-15\","
                + "\"guests\":2,\"rooms\":1,\"maxPrice\":\"412.50\",\"currency\":\"EUR\",\"firstName\":\"Erika\","
                + "\"lastName\":\"Muster\",\"email\":\"erika@example.org\",\"phone\":\"+49 170 1234567\"}";
    }

    private static LiteApi.HotelOffer offer(String hotelId, double latitude, double longitude, String net,
                                            String currency) {
        return offer(hotelId, latitude, longitude, net, currency, 3.0);
    }

    private static LiteApi.HotelOffer offer(String hotelId, double latitude, double longitude, String net,
                                            String currency, Double stars) {
        return new LiteApi.HotelOffer(hotelId, "Hotel " + hotelId, "Via " + hotelId, "IT", latitude, longitude, stars,
                8.1, null, "OF-" + hotelId + "-" + net, "Doppelzimmer", "Frühstück", true, null, new BigDecimal(net),
                currency, null);
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
