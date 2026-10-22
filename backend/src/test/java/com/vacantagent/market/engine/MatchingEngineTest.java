package com.vacantagent.market.engine;

import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.logic.MarketRules;
import com.vacantagent.market.matching.LimitStrategy;
import com.vacantagent.market.matching.MatchResult;
import com.vacantagent.market.matching.entities.OrderBook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static com.vacantagent.market.MarketFixtures.bookOffer;
import static com.vacantagent.market.MarketFixtures.bookOrder;
import static org.assertj.core.api.Assertions.assertThat;

/** Tests the engine without Spring: once synchronously via handle(), once with its real thread. */
class MatchingEngineTest {

    private final List<Agreement> sink = new CopyOnWriteArrayList<>();
    private CompletableFuture<Agreement> stored = null;     // null: every agreement counts as stored at once
    private final MatchingEngine engine = new MatchingEngine(
            new OrderBook(), new LimitStrategy(new MarketRules(100_00)), this::store, 1_000);

    @AfterEach
    void tearDown() throws InterruptedException {
        engine.stop();
    }

    @Test
    void handleIsSynchronousAndSendsMatchesToTheSink() {
        engine.restoreOffer(bookOffer(1, 10_00, 10_00));

        MatchResult result = engine.handle(new MatchingEngine.SubmitOrder(bookOrder(100, 12_00, 12_00)));

        assertThat(result).isInstanceOf(MatchResult.Matched.class);
        assertThat(sink).extracting(a -> a.getProviderOffer().getId()).containsExactly(1L);
    }

    @Test
    void cancelRemovesWaitingEntriesOnly() {
        engine.handle(new MatchingEngine.SubmitOrder(bookOrder(100, 5_00, 5_00)));

        assertThat(engine.handle(new MatchingEngine.CancelOrder(100))).isInstanceOf(MatchResult.Cancelled.class);
        assertThat(engine.handle(new MatchingEngine.CancelOrder(100))).isInstanceOf(MatchResult.NotFound.class);
    }

    @Test
    void expireRemovesOnlyEntriesWhoseTimeIsUp() {
        engine.restoreOffer(bookOffer(1, 50_00, 50_00, 100));
        engine.restoreOffer(bookOffer(2, 50_00, 50_00, 200));
        engine.restoreOffer(bookOffer(3, 50_00, 50_00));
        engine.restoreOrder(bookOrder(100, 1_00, 1_00, 100));
        engine.restoreOrder(bookOrder(101, 1_00, 1_00));

        assertThat(engine.handle(new MatchingEngine.ExpireEntries(100))).isEqualTo(new MatchResult.Expired(1, 1));
        assertThat(engine.handle(new MatchingEngine.ExpireEntries(100))).isEqualTo(new MatchResult.Expired(0, 0));
        assertThat(engine.waitingOffers()).isEqualTo(2);
        assertThat(engine.waitingOrders()).isEqualTo(1);
    }

    @Test
    void expireIgnoresEntriesThatAlreadyLeftTheBook() {
        engine.restoreOffer(bookOffer(1, 50_00, 50_00, 100));
        engine.handle(new MatchingEngine.CancelOffer(1));
        engine.restoreOffer(bookOffer(1, 50_00, 50_00, 300));    // same id back with a later expiry

        assertThat(engine.handle(new MatchingEngine.ExpireEntries(200))).isEqualTo(new MatchResult.Expired(0, 0));
        assertThat(engine.waitingOffers()).isEqualTo(1);
        assertThat(engine.handle(new MatchingEngine.ExpireEntries(300))).isEqualTo(new MatchResult.Expired(1, 0));
    }

    @Test
    void matchedReplyWaitsUntilTheAgreementIsStored() throws Exception {
        stored = new CompletableFuture<>();
        engine.start();
        engine.submit(new MatchingEngine.SubmitOffer(bookOffer(1, 10_00, 10_00))).get(1, TimeUnit.SECONDS);

        CompletableFuture<MatchResult> reply = engine.submit(new MatchingEngine.SubmitOrder(bookOrder(100, 12_00, 12_00)));
        MatchResult nextReply = engine.submit(new MatchingEngine.SubmitOrder(bookOrder(101, 1_00, 1_00)))
                .get(1, TimeUnit.SECONDS);   // the engine keeps matching meanwhile

        assertThat(nextReply).isInstanceOf(MatchResult.Resting.class);
        assertThat(reply).isNotDone();
        stored.complete(sink.getFirst());
        assertThat(reply.get(1, TimeUnit.SECONDS)).isInstanceOf(MatchResult.Matched.class);
    }

    @Test
    void failedStoreFailsTheReply() {
        stored = new CompletableFuture<>();
        engine.start();
        engine.submit(new MatchingEngine.SubmitOffer(bookOffer(1, 10_00, 10_00))).join();

        CompletableFuture<MatchResult> reply = engine.submit(new MatchingEngine.SubmitOrder(bookOrder(100, 12_00, 12_00)));
        stored.completeExceptionally(new IllegalStateException("database down"));

        assertThat(reply).failsWithin(1, TimeUnit.SECONDS);
    }

    @Test
    void manyThreadsSubmittingStillPairEachOfferOnce() throws Exception {
        engine.start();
        int n = 10_000;
        List<CompletableFuture<MatchResult>> futures = new ArrayList<>();

        Thread providers = Thread.ofPlatform().start(() -> {
            for (int i = 0; i < n; i++) {
                futures.add(engine.submit(new MatchingEngine.SubmitOffer(bookOffer(i, 10_00, 10_00))));
            }
        });
        List<CompletableFuture<MatchResult>> orderFutures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            orderFutures.add(engine.submit(new MatchingEngine.SubmitOrder(bookOrder(i, 10_00, 10_00))));
        }
        providers.join();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        CompletableFuture.allOf(orderFutures.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);

        assertThat(sink).hasSize(n);
        assertThat(sink).extracting(a -> a.getProviderOffer().getId()).doesNotHaveDuplicates();
        assertThat(sink).extracting(a -> a.getConsumerOrder().getId()).doesNotHaveDuplicates();
        assertThat(engine.waitingOffers()).isZero();
        assertThat(engine.waitingOrders()).isZero();
    }

    private CompletableFuture<Agreement> store(Agreement agreement) {
        sink.add(agreement);
        return stored != null ? stored : CompletableFuture.completedFuture(agreement);
    }
}
