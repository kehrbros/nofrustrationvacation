package com.vacantagent.market.api;

import com.vacantagent.common.money.Money;
import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.entities.ConsumerOrder;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.entities.Submission;
import com.vacantagent.market.logic.IMarketLogic;
import com.vacantagent.market.matching.MatchResult;
import com.vacantagent.user.entities.AppUser;
import com.vacantagent.user.logic.AppUserProvisioning;
import com.vacantagent.user.security.AppUserAuthentication;
import com.vacantagent.user.security.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.vacantagent.market.MarketFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP mapping of the market API. MarketLogic is mocked, matching itself is covered by the market and engine tests. */
@SpringBootTest
@AutoConfigureMockMvc
class MarketControllerTest {

    private static final String ORDER = """
            {"maxCostPerMillionInputTokens":{"amount":"11.00","currency":"USD"},
             "maxCostPerMillionOutputTokens":{"amount":"13.00","currency":"USD"},
             "marketWaitTime":"PT5M","modelProvider":"%s","modelName":"%s"}""";
    private static final String OFFER = """
            {"pricePerMillionInputTokens":{"amount":"10.00","currency":"USD"},
             "pricePerMillionOutputTokens":{"amount":"12.50","currency":"USD"},
             "modelProvider":"%s","modelName":"%s","totalTokens":1000}""";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @MockitoBean
    private IMarketLogic market;

    private long appUserId;
    private RequestPostProcessor user;

    @BeforeEach
    void setUp() {
        AppUser appUser = appUserProvisioning.findOrCreate("market-user", null, null);
        jdbcTemplate.update("UPDATE app_user SET preferred_currency = NULL WHERE id = ?", appUser.getId());
        appUserId = appUser.getId();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("market-user").build();
        user = authentication(new AppUserAuthentication(new CurrentUser(appUserId, "market-user"), jwt, List.of()));
    }

    @Test
    void matchedOrderReturnsTheAgreement() throws Exception {
        when(market.acceptOrder(any(), eq(appUserId))).thenReturn(submission(5, new MatchResult.Matched(agreement())));

        performAsync(post("/api/v1/market/orders").content(ORDER.formatted("openai", "gpt-5")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.status").value("MATCHED"))
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.agreement.id").value(99))
                .andExpect(jsonPath("$.agreement.consumerOrderId").value(5))
                .andExpect(jsonPath("$.agreement.providerOfferId").value(7))
                .andExpect(jsonPath("$.agreement.modelProvider").value("openai"))
                .andExpect(jsonPath("$.agreement.modelName").value("gpt-5"))
                .andExpect(jsonPath("$.agreement.agreedPricePerMillionOutputTokens.amount").value("12.500000"))
                .andExpect(jsonPath("$.agreement.agreedPricePerMillionOutputTokens.currency").value("USD"));
    }

    @Test
    void orderWithoutMatchRestsAndBlankModelValuesMeanAny() throws Exception {
        when(market.acceptOrder(any(), eq(appUserId))).thenReturn(submission(5, new MatchResult.Resting()));

        performAsync(post("/api/v1/market/orders").content(ORDER.formatted(" ", "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESTING"))
                .andExpect(jsonPath("$.agreement").doesNotExist());

        ArgumentCaptor<OrderDto> order = ArgumentCaptor.forClass(OrderDto.class);
        verify(market).acceptOrder(order.capture(), eq(appUserId));
        assertThat(order.getValue().modelProvider()).isNull();
        assertThat(order.getValue().modelName()).isNull();
    }

    @Test
    void modelValuesAreTrimmed() throws Exception {
        when(market.acceptOrder(any(), eq(appUserId))).thenReturn(submission(5, new MatchResult.Resting()));
        when(market.acceptOffer(any(), eq(appUserId))).thenReturn(submission(7, new MatchResult.Resting()));

        performAsync(post("/api/v1/market/orders").content(ORDER.formatted("  openai ", "\\tgpt-5 ")))
                .andExpect(status().isCreated());
        performAsync(post("/api/v1/market/offers").content(OFFER.formatted(" openai", "gpt-5  ")))
                .andExpect(status().isCreated());

        ArgumentCaptor<OrderDto> order = ArgumentCaptor.forClass(OrderDto.class);
        verify(market).acceptOrder(order.capture(), eq(appUserId));
        assertThat(order.getValue().modelProvider()).isEqualTo("openai");
        assertThat(order.getValue().modelName()).isEqualTo("gpt-5");
        ArgumentCaptor<OfferDto> offer = ArgumentCaptor.forClass(OfferDto.class);
        verify(market).acceptOffer(offer.capture(), eq(appUserId));
        assertThat(offer.getValue().modelProvider()).isEqualTo("openai");
        assertThat(offer.getValue().modelName()).isEqualTo("gpt-5");
    }

    @Test
    void rejectedOrderReturns422WithReason() throws Exception {
        when(market.acceptOrder(any(), eq(appUserId)))
                .thenReturn(submission(5, new MatchResult.Rejected("Order violates market rules")));

        performAsync(post("/api/v1/market/orders").content(ORDER.formatted("openai", "gpt-5")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reason").value("Order violates market rules"));
    }

    @Test
    void orderWithModelButWithoutProviderIsRefused() throws Exception {
        mockMvc.perform(json(post("/api/v1/market/orders")).content(ORDER.formatted("", "gpt-5")))
                .andExpect(status().isBadRequest());

        verify(market, never()).acceptOrder(any(), anyLong());
    }

    @Test
    void offerIsPlacedForTheAuthenticatedUser() throws Exception {
        when(market.acceptOffer(any(), eq(appUserId))).thenReturn(submission(7, new MatchResult.Resting()));

        performAsync(post("/api/v1/market/offers").content(OFFER.formatted("openai", "gpt-5")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.status").value("RESTING"));

        ArgumentCaptor<OfferDto> offer = ArgumentCaptor.forClass(OfferDto.class);
        verify(market).acceptOffer(offer.capture(), eq(appUserId));
        assertThat(offer.getValue().modelProvider()).isEqualTo("openai");
        assertThat(offer.getValue().modelName()).isEqualTo("gpt-5");
    }

    @Test
    void offerWithoutProviderOrModelIsRefused() throws Exception {
        mockMvc.perform(json(post("/api/v1/market/offers")).content(OFFER.formatted("openai", " ")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(json(post("/api/v1/market/offers")).content(OFFER.formatted("", "gpt-5")))
                .andExpect(status().isBadRequest());

        verify(market, never()).acceptOffer(any(), anyLong());
    }

    @Test
    void ownerCancelsWaitingOrder() throws Exception {
        when(market.isOrderOwnedBy(5, appUserId)).thenReturn(true);
        when(market.cancelOrder(5)).thenReturn(CompletableFuture.completedFuture(new MatchResult.Cancelled()));

        performAsync(delete("/api/v1/market/orders/5")).andExpect(status().isNoContent());
    }

    @Test
    void cancellingAnOrderThatIsNotWaitingReturns404() throws Exception {
        when(market.isOrderOwnedBy(5, appUserId)).thenReturn(true);
        when(market.cancelOrder(5)).thenReturn(CompletableFuture.completedFuture(new MatchResult.NotFound()));

        performAsync(delete("/api/v1/market/orders/5")).andExpect(status().isNotFound());
    }

    @Test
    void ordersOfOtherUsersCannotBeCancelled() throws Exception {
        when(market.isOrderOwnedBy(5, appUserId)).thenReturn(false);

        performAsync(delete("/api/v1/market/orders/5")).andExpect(status().isNotFound());

        verify(market, never()).cancelOrder(anyLong());
    }

    @Test
    void ownerCancelsWaitingOffer() throws Exception {
        when(market.isOfferOwnedBy(7, appUserId)).thenReturn(true);
        when(market.cancelOffer(7)).thenReturn(CompletableFuture.completedFuture(new MatchResult.Cancelled()));

        performAsync(delete("/api/v1/market/offers/7")).andExpect(status().isNoContent());
    }

    @Test
    void offersOfOtherUsersCannotBeCancelled() throws Exception {
        when(market.isOfferOwnedBy(7, appUserId)).thenReturn(false);

        performAsync(delete("/api/v1/market/offers/7")).andExpect(status().isNotFound());

        verify(market, never()).cancelOffer(anyLong());
    }

    /** The endpoints return futures: perform, then dispatch the completed async result. */
    private ResultActions performAsync(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult started = mockMvc.perform(json(builder)).andExpect(request().asyncStarted()).andReturn();
        return mockMvc.perform(asyncDispatch(started));
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder) {
        return builder.with(user).with(csrf()).contentType(MediaType.APPLICATION_JSON);
    }

    private static Submission submission(long id, MatchResult result) {
        return new Submission(id, CompletableFuture.completedFuture(result));
    }

    private static Agreement agreement() {
        ConsumerOrder order = withId(new ConsumerOrder(null, null, null, null, "openai", "gpt-5", null), 5);
        ProviderOffer offer = withId(new ProviderOffer(Money.of("10.00"), Money.of("12.50"), null, "openai", "gpt-5",
                null, null, 1000L, 1000L, null), 7);
        return withId(new Agreement(order, offer, OffsetDateTime.now(), null, Money.of("10.00"), Money.of("12.50")), 99);
    }
}