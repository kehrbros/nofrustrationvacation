package com.vacantagent.market.api;

import com.vacantagent.common.money.Money;
import com.vacantagent.market.dto.OfferDto;
import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.dto.SubmissionDto;
import com.vacantagent.market.dto.SubmissionDto.SubmissionStatus;
import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.entities.ConsumerOrder;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.entities.Submission;
import com.vacantagent.market.logic.IMarketLogic;
import com.vacantagent.market.matching.MatchResult;
import com.vacantagent.onboarding.logic.ProviderOnboarding;
import com.vacantagent.user.entities.AppUser;
import com.vacantagent.user.logic.AppUserProvisioning;
import com.vacantagent.user.security.AppUserAuthentication;
import com.vacantagent.user.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.vacantagent.market.MarketFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MCP tools of the market. MarketLogic is mocked, matching itself is covered by the market and engine tests. */
@SpringBootTest
class MarketToolsTest {

    @Autowired
    private MarketTools tools;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @MockitoBean
    private IMarketLogic market;
    @MockitoBean
    private ProviderOnboarding providerOnboarding;

    private long appUserId;

    @BeforeEach
    void setUp() {
        AppUser appUser = appUserProvisioning.findOrCreate("market-user", null, null);
        jdbcTemplate.update("UPDATE app_user SET preferred_currency = NULL WHERE id = ?", appUser.getId());
        appUserId = appUser.getId();
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("market-user").build();
        SecurityContextHolder.getContext().setAuthentication(
                new AppUserAuthentication(new CurrentUser(appUserId, "market-user"), jwt, List.of()));
        when(market.expireEntries()).thenReturn(CompletableFuture.completedFuture(new MatchResult.Expired(0, 0)));
        when(providerOnboarding.mayPlaceOffers(appUserId)).thenReturn(true);
    }

    @Test
    void offerRequiresACompletedProviderOnboarding() {
        when(providerOnboarding.mayPlaceOffers(appUserId)).thenReturn(false);

        assertThatThrownBy(() -> tools.placeOffer("1.25", "10.00", "USD", 42L, null, null))
                .isInstanceOf(InvalidMarketRequestException.class)
                .hasMessageContaining("onboarding");
        verify(market, never()).acceptOffer(any(), anyLong());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matchedOrderReturnsTheAgreement() {
        when(market.acceptOrderAndAwaitMatch(any(), eq(appUserId)))
                .thenReturn(submission(5, new MatchResult.Matched(agreement())));

        SubmissionDto result = tools.placeOrder("11.00", "13.00", "USD", "PT20S", "openai", "gpt-5");

        assertThat(result.id()).isEqualTo(5);
        assertThat(result.status()).isEqualTo(SubmissionStatus.MATCHED);
        assertThat(result.agreement().id()).isEqualTo(99);
        assertThat(result.agreement().agreedPricePerMillionOutputTokens().amount()).isEqualByComparingTo("12.50");
        assertThat(result.agreement().agreedPricePerMillionOutputTokens().currency()).isEqualTo("USD");

        ArgumentCaptor<OrderDto> order = ArgumentCaptor.forClass(OrderDto.class);
        verify(market).acceptOrderAndAwaitMatch(order.capture(), eq(appUserId));
        assertThat(order.getValue().marketWaitTime()).isEqualTo(Duration.ofSeconds(20));
        assertThat(order.getValue().maxCostPerMillionInputTokens().amount()).isEqualByComparingTo("11.00");
    }

    @Test
    void orderWhoseWaitIsOverIsRejectedWithReasonAndBlankModelValuesMeanAny() {
        when(market.acceptOrderAndAwaitMatch(any(), eq(appUserId)))
                .thenReturn(submission(5, new MatchResult.Rejected("Order wait time is over")));

        SubmissionDto result = tools.placeOrder("1.00", "1.00", "USD", "PT0S", " ", null);

        assertThat(result.status()).isEqualTo(SubmissionStatus.REJECTED);
        assertThat(result.reason()).isEqualTo("Order wait time is over");
        ArgumentCaptor<OrderDto> order = ArgumentCaptor.forClass(OrderDto.class);
        verify(market).acceptOrderAndAwaitMatch(order.capture(), eq(appUserId));
        assertThat(order.getValue().modelProvider()).isNull();
        assertThat(order.getValue().modelName()).isNull();
    }

    @Test
    void cancelledWhileWaitingIsReported() {
        when(market.acceptOrderAndAwaitMatch(any(), eq(appUserId)))
                .thenReturn(submission(5, new MatchResult.Cancelled()));

        assertThat(tools.placeOrder("1.00", "1.00", "USD", "PT10S", "openai", "gpt-5").status())
                .isEqualTo(SubmissionStatus.CANCELLED);
    }

    @Test
    void invalidWaitTimesAreRefused() {
        for (String waitTime : new String[]{null, "soon", "-PT1S", "PT61S"}) {
            assertThatThrownBy(() -> tools.placeOrder("1.00", "1.00", "USD", waitTime, "openai", "gpt-5"))
                    .isInstanceOf(InvalidMarketRequestException.class);
        }
        verify(market, never()).acceptOrderAndAwaitMatch(any(), anyLong());
    }

    @Test
    void invalidPricesAreRefused() {
        assertThatThrownBy(() -> tools.placeOrder("cheap", "1.00", "USD", "PT10S", "openai", "gpt-5"))
                .isInstanceOf(InvalidMarketRequestException.class);
    }

    @Test
    void orderWithModelButWithoutProviderIsRefused() {
        assertThatThrownBy(() -> tools.placeOrder("1.00", "1.00", "USD", "PT10S", "", "gpt-5"))
                .isInstanceOf(InvalidMarketRequestException.class);

        verify(market, never()).acceptOrderAndAwaitMatch(any(), anyLong());
    }

    @Test
    void offerIsPlacedForTheAuthenticatedUserWithItsAgent() {
        when(market.isAgentOwnedBy(42, appUserId)).thenReturn(true);
        when(market.acceptOffer(any(), eq(appUserId))).thenReturn(submission(7, new MatchResult.Resting()));

        SubmissionDto result = tools.placeOffer("10.00", "12.50", "USD", 42L, "2026-11-30T23:59:59+01:00", 1000L);

        assertThat(result.id()).isEqualTo(7);
        assertThat(result.status()).isEqualTo(SubmissionStatus.RESTING);
        ArgumentCaptor<OfferDto> offer = ArgumentCaptor.forClass(OfferDto.class);
        verify(market).acceptOffer(offer.capture(), eq(appUserId));
        assertThat(offer.getValue().agentId()).isEqualTo(42L);
        assertThat(offer.getValue().offeredUntil()).isEqualTo(OffsetDateTime.parse("2026-11-30T23:59:59+01:00"));
        assertThat(offer.getValue().totalTokens()).isEqualTo(1000L);
    }

    @Test
    void offerWithoutAgentIsRefused() {
        assertThatThrownBy(() -> tools.placeOffer("10.00", "12.50", "USD", null, null, null))
                .hasMessage("Offer requires agentId");
        verify(market, never()).acceptOffer(any(), anyLong());
    }

    @Test
    void agentsOfOtherUsersCannotBeUsed() {
        when(market.isAgentOwnedBy(42, appUserId)).thenReturn(false);

        assertThatThrownBy(() -> tools.placeOffer("10.00", "12.50", "USD", 42L, null, null))
                .hasMessage("Agent 42 not found");
        verify(market, never()).acceptOffer(any(), anyLong());
    }

    @Test
    void ownerCancelsWaitingOrderAndOffer() {
        when(market.isOrderOwnedBy(5, appUserId)).thenReturn(true);
        when(market.cancelOrder(5)).thenReturn(CompletableFuture.completedFuture(new MatchResult.Cancelled()));
        when(market.isOfferOwnedBy(7, appUserId)).thenReturn(true);
        when(market.cancelOffer(7)).thenReturn(CompletableFuture.completedFuture(new MatchResult.Cancelled()));

        assertThat(tools.cancelOrder(5)).isEqualTo("Order 5 cancelled");
        assertThat(tools.cancelOffer(7)).isEqualTo("Offer 7 cancelled");
    }

    @Test
    void cancellingAnOrderThatIsNotWaitingFails() {
        when(market.isOrderOwnedBy(5, appUserId)).thenReturn(true);
        when(market.cancelOrder(5)).thenReturn(CompletableFuture.completedFuture(new MatchResult.NotFound()));

        assertThatThrownBy(() -> tools.cancelOrder(5)).hasMessage("Order 5 is not waiting");
    }

    @Test
    void entriesOfOtherUsersCannotBeCancelled() {
        when(market.isOrderOwnedBy(5, appUserId)).thenReturn(false);
        when(market.isOfferOwnedBy(7, appUserId)).thenReturn(false);

        assertThatThrownBy(() -> tools.cancelOrder(5)).hasMessage("Order 5 is not waiting");
        assertThatThrownBy(() -> tools.cancelOffer(7)).hasMessage("Offer 7 is not waiting");
        verify(market, never()).cancelOrder(anyLong());
        verify(market, never()).cancelOffer(anyLong());
    }

    @Test
    void toolsRequireAnAuthenticatedUser() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> tools.cancelOrder(5)).hasMessage("No authenticated user");
    }

    private static Submission submission(long id, MatchResult result) {
        return new Submission(id, CompletableFuture.completedFuture(result));
    }

    private static Agreement agreement() {
        ConsumerOrder order = withId(new ConsumerOrder(null, null, null, null, "openai", "gpt-5", null, null, null), 5);
        ProviderOffer offer = withId(new ProviderOffer(Money.of("10.00"), Money.of("12.50"), null, 3, "openai", "gpt-5",
                null, null, 1000L, 1000L, null, null), 7);
        return withId(new Agreement(order, offer, OffsetDateTime.now(), null, Money.of("10.00"), Money.of("12.50")), 99);
    }
}