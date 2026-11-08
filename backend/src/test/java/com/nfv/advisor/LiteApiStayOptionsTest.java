package com.nfv.advisor;

import com.jayway.jsonpath.JsonPath;
import com.nfv.advisor.logic.StayOptions;
import com.nfv.advisor.logic.StayOptions.BookedStay;
import com.nfv.advisor.logic.StayOptions.InvalidStaySearchException;
import com.nfv.advisor.logic.StayOptions.StayOffer;
import com.nfv.advisor.logic.StayOptions.StaySearch;
import com.nfv.advisor.logic.StayOptions.StaySearchUnavailableException;
import com.nfv.assistant.logic.StructuredExtraction;
import com.nfv.bookings.logic.LiteApi;
import com.nfv.bookings.logic.LiteApiUnavailableException;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The adapter of the agent on the search for alternatives: hard check and booked stay from {@code bookings}, never the
 * assistant. LiteAPI and the assistant are mocked, see Docs/advisor.md#search_accommodations.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LiteApiStayOptionsTest {
    private static final String TRIP = "{\"name\":\"Italien\",\"startAt\":\"2027-07-10T00:00:00+02:00\","
            + "\"endAt\":\"2027-07-20T23:59:59+02:00\"}";
    private static final String BOOKING = "{\"source\":\"DOCUMENT\",\"name\":\"Hotel Adler\",\"addressLine\":\"Via Roma 1\","
            + "\"latitude\":41.9028,\"longitude\":12.4964,\"checkIn\":\"2027-07-12T15:00:00\","
            + "\"checkOut\":\"2027-07-15T10:00:00\",\"guests\":2,\"rooms\":1,\"price\":\"412.50\",\"currency\":\"EUR\"}";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private StayOptions stayOptions;
    @MockitoBean
    private LiteApi liteApi;
    @MockitoBean
    private StructuredExtraction extraction;

    private long userId;
    private long tripId;
    private long stopId;
    private long bookingId;

    @BeforeEach
    void setUp() throws Exception {
        String username = "stay-options-" + UUID.randomUUID();
        AppUser appUser = appUserProvisioning.findOrCreate(username, null, null);
        userId = appUser.getId();
        RequestPostProcessor user = authentication(new AppUserAuthentication(new CurrentUser(userId, username),
                Jwt.withTokenValue("token").header("alg", "none").subject(username).build(), List.of()));
        tripId = id(mockMvc.perform(post("/api/v1/trips").with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(TRIP)).andExpect(status().isCreated()));
        String trip = mockMvc.perform(post("/api/v1/trips/{id}/stops", tripId).with(user).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Rom\",\"latitude\":-41.2865,\"longitude\":174.7762}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        stopId = ((Number) JsonPath.read(trip, "$.stops[0].id")).longValue();
        bookingId = id(mockMvc.perform(post("/api/v1/stops/{id}/accommodation-bookings", stopId).with(user).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(BOOKING)).andExpect(status().isCreated()));
    }

    @Test
    void bookedStayAtTheStopGivesTheCriteriaOfTheBooking() {
        BookedStay booked = stayOptions.bookedStay(userId, tripId, stopId).orElseThrow();

        assertThat(booked.bookingId()).isEqualTo(bookingId);
        assertThat(booked.latitude()).isEqualTo(41.9028);
        assertThat(booked.checkIn()).isEqualTo(LocalDate.of(2027, 7, 12));
        assertThat(booked.checkOut()).isEqualTo(LocalDate.of(2027, 7, 15));
        assertThat(booked.guests()).isEqualTo(2);
        assertThat(booked.maxPrice()).isEqualByComparingTo("412.50");
        assertThat(booked.currency()).isEqualTo("EUR");
        assertThat(stayOptions.bookedStay(userId, tripId, stopId + 1000)).isEmpty();
    }

    @Test
    void offersAreCheckedHardCheapestFirstWithoutTheAssistant() {
        when(liteApi.rates(any())).thenReturn(List.of(
                offer("good", 41.9050, 12.5000, "300.00", "EUR"),
                offer("cheap", 41.9030, 12.4970, "150.00", "EUR"),
                offer("cheap", 41.9030, 12.4970, "180.00", "EUR"),
                // 400 net + 10 % fee = 440 > 412.50
                offer("expensive", 41.9030, 12.4970, "400.00", "EUR"),
                offer("far", 42.0000, 12.4964, "100.00", "EUR"),
                offer("dollar", 41.9030, 12.4970, "100.00", "USD"),
                offer("third", 41.9040, 12.4980, "200.00", "EUR")));

        List<StayOffer> offers = stayOptions.search(userId, search(new BigDecimal("412.50"), 2));

        assertThat(offers).extracting(StayOffer::hotelId).containsExactly("cheap", "third");
        assertThat(offers.getFirst().price()).isEqualByComparingTo("165.00");
        assertThat(offers.getFirst().name()).isEqualTo("Hotel cheap");
        verify(extraction, never()).extract(anyLong(), anyString(), anyString(), any(), any());
    }

    @Test
    void failuresAreTranslated() {
        assertThatThrownBy(() -> stayOptions.search(userId, new StaySearch(41.9, 12.5, 90_000, LocalDate.of(2027, 7, 12),
                LocalDate.of(2027, 7, 13), 2, 1, null, "EUR", null, 3)))
                .isInstanceOf(InvalidStaySearchException.class);
        when(liteApi.rates(any())).thenThrow(new LiteApiUnavailableException("LiteAPI request failed"));
        assertThatThrownBy(() -> stayOptions.search(userId, search(null, 3)))
                .isInstanceOfSatisfying(StaySearchUnavailableException.class,
                        e -> assertThat(e.rateLimited()).isFalse());
    }

    private static StaySearch search(BigDecimal maxPrice, int maxResults) {
        return new StaySearch(41.9028, 12.4964, 5000, LocalDate.of(2027, 7, 12), LocalDate.of(2027, 7, 15), 2, 1,
                maxPrice, "EUR", null, maxResults);
    }

    private static LiteApi.HotelOffer offer(String hotelId, double latitude, double longitude, String net,
                                            String currency) {
        return new LiteApi.HotelOffer(hotelId, "Hotel " + hotelId, "Via " + hotelId, "IT", latitude, longitude, 3.0,
                8.1, null, "OF-" + hotelId + "-" + net, "Doppelzimmer", "Frühstück", true, null, new BigDecimal(net),
                currency, null);
    }

    private static long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
