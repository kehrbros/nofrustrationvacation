package com.nfv.bookings;

import com.jayway.jsonpath.JsonPath;
import com.nfv.bookings.entities.PlanPaymentStatus;
import com.nfv.bookings.entities.ReservationStatus;
import com.nfv.bookings.logic.LiteApi;
import com.nfv.bookings.logic.OfferUnavailableException;
import com.nfv.bookings.logic.PlanPayments;
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

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * All hotels of a plan with one PayPal approval, see Docs/incident-plans.md#one-click-execution. LiteAPI and
 * PayPal are mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PlanPaymentsTest {
    private static final String TRIP = "{\"name\":\"Südinsel\",\"startAt\":\"2027-07-10T00:00:00+12:00\","
            + "\"endAt\":\"2027-07-20T23:59:59+12:00\"}";
    private static final PlanPayments.Guest GUEST = new PlanPayments.Guest("Erika", "Muster", "erika@example.org",
            "+49 170 1234567");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private PlanPayments planPayments;
    @MockitoBean
    private LiteApi liteApi;
    @MockitoBean
    private PayPalCheckout checkout;

    private long appUserId;
    private long tripId;
    private long firstStopId;
    private long secondStopId;

    @BeforeEach
    void setUp() throws Exception {
        String username = "plans-" + UUID.randomUUID();
        AppUser appUser = appUserProvisioning.findOrCreate(username, null, null);
        appUserId = appUser.getId();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(username).build();
        RequestPostProcessor user = authentication(new AppUserAuthentication(new CurrentUser(appUserId, username), jwt,
                List.of()));
        tripId = ((Number) JsonPath.read(mockMvc.perform(post("/api/v1/trips").with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(TRIP)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Wellington\",\"latitude\":-41.2865,\"longitude\":174.7762}"))
                .andExpect(status().isCreated());
        String trip = mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Porirua\",\"latitude\":-41.1339,\"longitude\":174.8406}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        firstStopId = ((Number) JsonPath.read(trip, "$.stops[0].id")).longValue();
        secondStopId = ((Number) JsonPath.read(trip, "$.stops[1].id")).longValue();
        hotel("OF-1", "P-1", "sole", "Hotel Sole", "150.00");
        hotel("OF-2", "P-2", "luna", "Hotel Luna", "100.00");
        when(checkout.createOrder(anyString(), any(List.class), any(), any()))
                .thenReturn(new PayPalCheckout.CheckoutOrder("O-9", "https://paypal.test/O-9"));
    }

    @Test
    void allHotelsArePaidWithOneApprovalAndBookedAtTheStopsOfThePlan() {
        PlanPayments.PlanPaymentView reserved = planPayments.reserve(appUserId, stays(), GUEST, "/reisen/" + tripId);

        assertThat(reserved.status()).isEqualTo(PlanPaymentStatus.PAYMENT_PENDING);
        assertThat(reserved.total()).isEqualByComparingTo("275.00");
        assertThat(reserved.approveUrl()).isEqualTo("https://paypal.test/O-9");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<PayPalCheckout.Unit>> units = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<URI> returnUrl = ArgumentCaptor.forClass(URI.class);
        verify(checkout).createOrder(eq("plan-" + reserved.planPaymentId()), units.capture(), returnUrl.capture(),
                any());
        assertThat(units.getValue()).extracting(PayPalCheckout.Unit::amount).containsExactly(
                new PayPalAmount("EUR", new BigDecimal("165.00")), new PayPalAmount("EUR", new BigDecimal("110.00")));
        assertThat(returnUrl.getValue().toString()).endsWith("/reisen/" + tripId + "?planzahlung="
                + reserved.planPaymentId());
        long first = reserved.items().get(0).reservationId();
        long second = reserved.items().get(1).reservationId();
        when(checkout.authorizeUnits("O-9", "authorize-plan-" + reserved.planPaymentId())).thenReturn(Map.of(
                "stay-" + first, new PayPalCheckout.Payment("A-1", "CREATED", new PayPalAmount("EUR", new BigDecimal("165.00"))),
                "stay-" + second, new PayPalCheckout.Payment("A-2", "CREATED", new PayPalAmount("EUR", new BigDecimal("110.00")))));
        when(liteApi.book(eq("P-1"), any(), anyInt(), anyString())).thenReturn(new LiteApi.ProviderBooking("LB-1", "HC-1", "CONFIRMED"));
        when(liteApi.book(eq("P-2"), any(), anyInt(), anyString())).thenReturn(new LiteApi.ProviderBooking("LB-2", "HC-2", "CONFIRMED"));
        when(checkout.captureAuthorization(eq("A-1"), anyString())).thenReturn(new PayPalCheckout.Payment("C-1", "COMPLETED", null));
        when(checkout.captureAuthorization(eq("A-2"), anyString())).thenReturn(new PayPalCheckout.Payment("C-2", "COMPLETED", null));

        PlanPayments.PlanPaymentView secured = planPayments.secure(appUserId, reserved.planPaymentId());

        assertThat(secured.status()).isEqualTo(PlanPaymentStatus.SECURED);
        assertThat(secured.items()).extracting(PlanPayments.Item::status)
                .containsOnly(ReservationStatus.PROVIDER_BOOKED);
        verify(checkout, never()).captureAuthorization(anyString(), anyString());

        // the night on the way moves to the stop the plan made for it
        PlanPayments.PlanPaymentView settled = planPayments.settle(appUserId, reserved.planPaymentId(),
                Map.of("overnight", secondStopId));

        assertThat(settled.status()).isEqualTo(PlanPaymentStatus.SETTLED);
        assertThat(settled.items()).extracting(PlanPayments.Item::status).containsOnly(ReservationStatus.BOOKED);
        assertThat(settled.items()).extracting(PlanPayments.Item::stopId).containsExactly(secondStopId, firstStopId);
        assertThat(settled.items()).allSatisfy(item -> assertThat(item.bookingId()).isNotNull());
        // repeating changes nothing
        planPayments.settle(appUserId, reserved.planPaymentId(), Map.of("overnight", secondStopId));
        verify(checkout).captureAuthorization(eq("A-1"), anyString());
    }

    @Test
    void aHotelThatRefusesUndoesTheOthersAndNothingIsPaid() {
        PlanPayments.PlanPaymentView reserved = planPayments.reserve(appUserId, stays(), GUEST, "/reisen/" + tripId);
        long first = reserved.items().get(0).reservationId();
        long second = reserved.items().get(1).reservationId();
        when(checkout.authorizeUnits(eq("O-9"), anyString())).thenReturn(Map.of(
                "stay-" + first, new PayPalCheckout.Payment("A-1", "CREATED", new PayPalAmount("EUR", new BigDecimal("165.00"))),
                "stay-" + second, new PayPalCheckout.Payment("A-2", "CREATED", new PayPalAmount("EUR", new BigDecimal("110.00")))));
        when(liteApi.book(eq("P-1"), any(), anyInt(), anyString())).thenReturn(new LiteApi.ProviderBooking("LB-1", "HC-1", "CONFIRMED"));
        when(liteApi.book(eq("P-2"), any(), anyInt(), anyString())).thenThrow(new OfferUnavailableException("4002", "sold out"));
        when(liteApi.cancel("LB-1")).thenReturn(new LiteApi.Cancellation("CANCELLED", new BigDecimal("150.00")));

        assertThatThrownBy(() -> planPayments.secure(appUserId, reserved.planPaymentId()))
                .isInstanceOf(OfferUnavailableException.class);

        PlanPayments.PlanPaymentView failed = planPayments.get(appUserId, reserved.planPaymentId());
        assertThat(failed.status()).isEqualTo(PlanPaymentStatus.FAILED);
        assertThat(failed.items()).extracting(PlanPayments.Item::status).containsOnly(ReservationStatus.FAILED);
        verify(liteApi).cancel("LB-1");
        verify(checkout).voidAuthorization(eq("A-1"), anyString());
        verify(checkout).voidAuthorization(eq("A-2"), anyString());
        verify(checkout, never()).captureAuthorization(anyString(), anyString());
    }

    private List<PlanPayments.PlanStay> stays() {
        LocalDate night = LocalDate.of(2027, 7, 12);
        return List.of(
                new PlanPayments.PlanStay("overnight", firstStopId, null, "sole", "OF-1", night, night.plusDays(1), 2,
                        1, new BigDecimal("165.00"), "EUR"),
                new PlanPayments.PlanStay("target", firstStopId, null, "luna", "OF-2", night.plusDays(1),
                        night.plusDays(2), 2, 1, new BigDecimal("110.00"), "EUR"));
    }

    private void hotel(String offerId, String prebookId, String hotelId, String name, String net) {
        when(liteApi.prebook(offerId)).thenReturn(new LiteApi.Prebook(prebookId, new BigDecimal(net), "EUR", null, false,
                true, LocalDateTime.of(2027, 7, 11, 12, 0)));
        when(liteApi.hotel(hotelId)).thenReturn(new LiteApi.HotelDetails(hotelId, name, "Lambton Quay 5", "Wellington",
                "NZ", -41.2800, 174.7760, "+64 4 123", null, null, LocalTime.of(14, 0), null, LocalTime.of(11, 0)));
    }
}
