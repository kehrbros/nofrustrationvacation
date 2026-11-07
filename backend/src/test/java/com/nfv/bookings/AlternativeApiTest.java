package com.nfv.bookings;

import com.jayway.jsonpath.JsonPath;
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

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private RequestPostProcessor user;
    private long tripId;
    private long bookingId;

    @BeforeEach
    void setUp() throws Exception {
        user = login("alternatives-" + UUID.randomUUID());
        tripId = id(mockMvc.perform(post("/api/v1/trips").with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(TRIP)).andExpect(status().isCreated()));
        String trip = mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rom\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long stopId = ((Number) JsonPath.read(trip, "$.stops[0].id")).longValue();
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
                .andExpect(jsonPath("$.guests").value(2))
                .andExpect(jsonPath("$.rooms").value(1))
                .andExpect(jsonPath("$.maxPrice").value("412.50"))
                .andExpect(jsonPath("$.currency").value("EUR"))
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
        when(extraction.extract(anyLong(), anyString(), eq("alternative_ranking"), any(), any()))
                .thenReturn(jsonMapper.readTree("""
                        {"ranking": [{"hotelId": "good", "reason": "Gleiche Kategorie, ruhig gelegen."},
                                     {"hotelId": "invented", "reason": "x"},
                                     {"hotelId": "cheap", "reason": "Günstig, gleich nebenan."}]}"""));

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
                .andExpect(jsonPath("$.offersChecked").value(6));
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
        verify(extraction, never()).extract(anyLong(), anyString(), anyString(), any(), any());
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

        mockMvc.perform(post("/api/v1/bookings/{id}/cancellation", bookingId).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        mockMvc.perform(get("/api/v1/trips/{id}/bookings", tripId).with(user))
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void hotelInAnotherPlaceMovesTheStopAndPlansItsRoutesAgain() throws Exception {
        // trip Florenz -> Rom, the booking in Rom gets replaced by a hotel in Tivoli, about 26 km away
        String florenz = mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Florenz\",\"latitude\":43.7696,"
                                + "\"longitude\":11.2558,\"departureAt\":\"2027-07-12T09:00:00+02:00\",\"position\":0}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long romId = ((Number) JsonPath.read(florenz, "$.stops[1].id")).longValue();
        mockMvc.perform(put("/api/v1/trips/{id}/stops/{stopId}", tripId, romId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rom\",\"latitude\":41.9028,\"longitude\":12.4964}"))
                .andExpect(status().isOk());
        long routeId = ((Number) JsonPath.read(mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andReturn().getResponse().getContentAsString(), "$.routes[0].id")).longValue();
        prebookAndHotel("150.00");
        when(liteApi.hotel("sole")).thenReturn(new LiteApi.HotelDetails("sole", "Hotel Sole", "Via Tiburtina 5",
                "Tivoli", "IT", 41.9631, 12.7980, "+39 0774 123", null, null, LocalTime.of(14, 0),
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
                .andExpect(jsonPath("$.checkInUntil").value("2027-07-12T22:00:00+02:00"));

        mockPaymentAndBooking(reservationId, "O-1");
        mockMvc.perform(post("/api/v1/booking-reservations/{id}/booking", reservationId).with(user).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BOOKED"))
                .andExpect(jsonPath("$.replannedRouteIds", contains((int) routeId)));
        mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andExpect(jsonPath("$.stops[1].name").value("Tivoli"))
                .andExpect(jsonPath("$.stops[1].latitude").value(41.9631))
                .andExpect(jsonPath("$.stops[1].longitude").value(12.7980))
                .andExpect(jsonPath("$.stops[0].name").value("Florenz"));
    }

    @Test
    void hotelInTheSamePlaceLeavesTheStop() throws Exception {
        long stopId = ((Number) JsonPath.read(mockMvc.perform(get("/api/v1/trips/{id}", tripId).with(user))
                .andReturn().getResponse().getContentAsString(), "$.stops[0].id")).longValue();
        mockMvc.perform(put("/api/v1/trips/{id}/stops/{stopId}", tripId, stopId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Rom Zentrum\",\"latitude\":41.9028,\"longitude\":12.4964}"))
                .andExpect(status().isOk());
        prebookAndHotel("150.00");
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
                .andExpect(jsonPath("$.stops[0].name").value("Rom Zentrum"))
                .andExpect(jsonPath("$.stops[0].latitude").value(41.9028));
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

    private static String reservation() {
        return "{\"hotelId\":\"sole\",\"offerId\":\"OF-1\",\"checkIn\":\"2027-07-12\",\"checkOut\":\"2027-07-15\","
                + "\"guests\":2,\"rooms\":1,\"maxPrice\":\"412.50\",\"currency\":\"EUR\",\"firstName\":\"Erika\","
                + "\"lastName\":\"Muster\",\"email\":\"erika@example.org\",\"phone\":\"+49 170 1234567\"}";
    }

    private static LiteApi.HotelOffer offer(String hotelId, double latitude, double longitude, String net,
                                            String currency) {
        return new LiteApi.HotelOffer(hotelId, "Hotel " + hotelId, "Via " + hotelId, "IT", latitude, longitude, 3.0,
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
