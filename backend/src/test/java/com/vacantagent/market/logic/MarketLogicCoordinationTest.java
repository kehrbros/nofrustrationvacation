package com.vacantagent.market.logic;

import com.vacantagent.common.money.Money;
import com.vacantagent.market.config.MarketProperties;
import com.vacantagent.agent.logic.AgentLogic;
import com.vacantagent.market.data.AgreementRepository;
import com.vacantagent.market.data.AgreementWriter;
import com.vacantagent.market.data.ConsumerOrderRepository;
import com.vacantagent.market.data.ProviderOfferRepository;
import com.vacantagent.market.dto.OrderDto;
import com.vacantagent.market.engine.MatchingEngine;
import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.entities.ConsumerOrder;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.mapper.OfferMapper;
import com.vacantagent.market.mapper.OrderMapper;
import com.vacantagent.market.matching.LimitStrategy;
import com.vacantagent.market.matching.MatchResult;
import com.vacantagent.market.matching.entities.OrderBook;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.vacantagent.market.MarketFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** MarketLogic around a real engine thread, without Spring: agreements are persisted before a match completes. */
class MarketLogicCoordinationTest {

    private final MarketRules rules = new MarketRules(1_000_000_000L);
    private final MatchingEngine engine = new MatchingEngine(new OrderBook(), new LimitStrategy(rules), 1_000);
    private final AgreementWriter writer = mock(AgreementWriter.class);
    private final ConsumerOrderRepository orders = mock(ConsumerOrderRepository.class);
    private final OrderMapper orderMapper = mock(OrderMapper.class);
    private final MarketLogic market = new MarketLogic(engine, writer, rules, orders,
            mock(ProviderOfferRepository.class), mock(AgentLogic.class), mock(AgreementRepository.class),
            orderMapper, mock(OfferMapper.class), new MarketProperties(1_000_000_000L, 1_000, 1_000,
            Duration.ofSeconds(30), Duration.ofSeconds(5), Duration.ofSeconds(10)), Clock.systemUTC());

    private CompletableFuture<Agreement> stored;

    @BeforeEach
    void setUp() {
        stored = new CompletableFuture<>();
        when(writer.enqueue(any())).thenAnswer(invocation -> stored);
        when(orders.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        market.start();     // nothing to restore: the mocked repositories return empty lists
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        market.stop();
    }

    @Test
    void matchedResultCompletesOnceTheAgreementIsPersisted() throws Exception {
        assertThat(market.resubmitOffer(offer(1, "10.00")).get(1, TimeUnit.SECONDS)).isInstanceOf(MatchResult.Resting.class);

        CompletableFuture<MatchResult> matched = placeOrder(100, "12.00");
        MatchResult next = placeOrder(101, "1.00").get(1, TimeUnit.SECONDS);   // the engine keeps matching meanwhile

        assertThat(next).isInstanceOf(MatchResult.Resting.class);
        assertThat(matched).isNotDone();
        verify(writer).enqueue(any());
        stored.complete(null);
        assertThat(matched.get(1, TimeUnit.SECONDS)).isInstanceOf(MatchResult.Matched.class);
    }

    @Test
    void failedPersistFailsTheResult() throws Exception {
        market.resubmitOffer(offer(1, "10.00")).get(1, TimeUnit.SECONDS);

        CompletableFuture<MatchResult> matched = placeOrder(100, "12.00");
        stored.completeExceptionally(new IllegalStateException("database down"));

        assertThat(matched).failsWithin(1, TimeUnit.SECONDS);
    }

    @Test
    void resultsWithoutAgreementDoNotReachTheWriter() throws Exception {
        assertThat(placeOrder(100, "1.00").get(1, TimeUnit.SECONDS)).isInstanceOf(MatchResult.Resting.class);
        assertThat(market.cancelOrder(100).get(1, TimeUnit.SECONDS)).isInstanceOf(MatchResult.Cancelled.class);
        assertThat(market.cancelOrder(100).get(1, TimeUnit.SECONDS)).isInstanceOf(MatchResult.NotFound.class);
        assertThat(market.expireEntries().get(1, TimeUnit.SECONDS)).isEqualTo(new MatchResult.Expired(0, 0));

        verify(writer, never()).enqueue(any());
    }

    @Test
    void expiringWhileStoppedDoesNothing() throws Exception {
        market.stop();

        assertThat(market.expireEntries().get(1, TimeUnit.SECONDS)).isEqualTo(new MatchResult.Expired(0, 0));
    }

    @Test
    void awaitedOrderCompletesOnceALaterOfferMatchedAndIsPersisted() throws Exception {
        CompletableFuture<MatchResult> awaited = awaitOrder(100, "12.00", null);
        market.resubmitOffer(offer(1, "10.00"));

        verify(writer, timeout(1000)).enqueue(any());
        assertThat(awaited).isNotDone();
        stored.complete(null);
        assertThat(awaited.get(1, TimeUnit.SECONDS)).isInstanceOf(MatchResult.Matched.class);
    }

    @Test
    void awaitedOrderMatchedOnArrivalCompletesOncePersisted() throws Exception {
        market.resubmitOffer(offer(1, "10.00")).get(1, TimeUnit.SECONDS);
        CompletableFuture<MatchResult> awaited = awaitOrder(100, "12.00", null);

        verify(writer, timeout(1000)).enqueue(any());
        stored.complete(null);
        assertThat(awaited.get(1, TimeUnit.SECONDS)).isInstanceOf(MatchResult.Matched.class);
    }

    @Test
    void waitTimeOverEndsTheWaitAndRemovesTheOrder() throws Exception {
        CompletableFuture<MatchResult> awaited = awaitOrder(100, "1.00", Duration.ofMillis(100));

        assertThat(awaited.get(2, TimeUnit.SECONDS)).isEqualTo(new MatchResult.Rejected("Order wait time is over"));
        assertThat(market.cancelOrder(100).get(1, TimeUnit.SECONDS)).isInstanceOf(MatchResult.NotFound.class);
    }

    @Test
    void rejectedOnArrivalEndsTheWait() throws Exception {
        assertThat(awaitOrder(100, "1.00", Duration.ZERO).get(1, TimeUnit.SECONDS))
                .isEqualTo(new MatchResult.Rejected("Order wait time is over"));
    }

    @Test
    void cancelEndsTheWait() throws Exception {
        CompletableFuture<MatchResult> awaited = awaitOrder(100, "1.00", null);

        assertThat(market.cancelOrder(100).get(1, TimeUnit.SECONDS)).isInstanceOf(MatchResult.Cancelled.class);
        assertThat(awaited.get(1, TimeUnit.SECONDS)).isInstanceOf(MatchResult.Cancelled.class);
    }

    @Test
    void failedPersistFailsTheWait() {
        CompletableFuture<MatchResult> awaited = awaitOrder(100, "12.00", null);
        market.resubmitOffer(offer(1, "10.00"));

        verify(writer, timeout(1000)).enqueue(any());
        stored.completeExceptionally(new IllegalStateException("database down"));
        assertThat(awaited).failsWithin(1, TimeUnit.SECONDS);
    }

    @Test
    void stoppingFailsOpenWaits() throws Exception {
        CompletableFuture<MatchResult> awaited = awaitOrder(100, "1.00", null);
        market.cancelOrder(999).get(1, TimeUnit.SECONDS);   // order 100 has been processed

        market.stop();
        assertThat(awaited).failsWithin(1, TimeUnit.SECONDS);
    }

    private CompletableFuture<MatchResult> awaitOrder(long id, String maxPrice, @Nullable Duration waitTime) {
        ConsumerOrder order = withId(new ConsumerOrder(Money.of(maxPrice), Money.of(maxPrice), waitTime,
                waitTime == null ? null : OffsetDateTime.now(), null, null, null, null, null), id);
        when(orderMapper.toEntity(any(), anyLong())).thenReturn(order);
        return market.acceptOrderAndAwaitMatch(new OrderDto(null, null, null, null, null), 7).result();
    }

    private CompletableFuture<MatchResult> placeOrder(long id, String maxPrice) {
        ConsumerOrder order = withId(new ConsumerOrder(Money.of(maxPrice), Money.of(maxPrice), null, null,
                null, null, null, null, null), id);
        when(orderMapper.toEntity(any(), anyLong())).thenReturn(order);
        return market.acceptOrder(new OrderDto(null, null, null, null, null), 7).result();
    }

    private static ProviderOffer offer(long id, String price) {
        return withId(new ProviderOffer(Money.of(price), Money.of(price), null, 0, null, null, null, null,
                1000L, 1000L, null, null), id);
    }
}