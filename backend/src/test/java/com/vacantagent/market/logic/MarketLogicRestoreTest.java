package com.vacantagent.market.logic;

import com.vacantagent.common.money.Money;
import com.vacantagent.market.data.ConsumerOrderRepository;
import com.vacantagent.market.data.ProviderOfferRepository;
import com.vacantagent.market.engine.MatchingEngine;
import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.entities.ConsumerOrder;
import com.vacantagent.market.entities.ProviderOffer;
import com.vacantagent.market.matching.LimitStrategy;
import com.vacantagent.market.matching.MatchResult;
import com.vacantagent.market.matching.entities.OrderBook;
import com.vacantagent.user.entities.AppUser;
import com.vacantagent.user.logic.AppUserProvisioning;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;

import static com.vacantagent.market.MarketFixtures.emptyOrder;
import static org.assertj.core.api.Assertions.assertThat;

/** Reloading waiting offers and orders from the database into a fresh engine. */
@SpringBootTest
class MarketLogicRestoreTest {

    @Autowired
    private ProviderOfferRepository offers;
    @Autowired
    private ConsumerOrderRepository orders;
    @Autowired
    private MarketRules rules;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private AppUserProvisioning appUserProvisioning;

    @Test
    void restoresOnlyAvailableOffers() {
        ProviderOffer waiting = persist(offer("10.00", null, "1000"));
        ProviderOffer failed = persist(offer("10.00", OffsetDateTime.now(), "1000"));
        ProviderOffer invalid = persist(offer(null, null, "1000"));
        ProviderOffer noTokens = persist(offer("10.00", null, "0"));
        ProviderOffer unknownTokens = persist(offer("10.00", null, null));
        ProviderOffer agreed = persist(offer("10.00", null, "1000"));
        ConsumerOrder order = persist(emptyOrder());
        persist(new Agreement(order, agreed, OffsetDateTime.now(), null, Money.of("10"), Money.of("10")));
        ProviderOffer released = persist(offer("10.00", null, "1000"));
        Agreement abandoned = new Agreement(persist(emptyOrder()), released, OffsetDateTime.now(), null,
                Money.of("10"), Money.of("10"));
        abandoned.markError(OffsetDateTime.now());
        persist(abandoned);
        ProviderOffer expired = persist(new ProviderOffer(Money.of("10.00"), Money.of("10.00"),
                OffsetDateTime.now().minusMinutes(1), null, null, null, null, 1000L, 1000L, null));

        MatchingEngine engine = newEngine();
        MarketLogic.restoreOffers(engine, offers, rules);

        assertThat(cancel(engine, waiting)).isInstanceOf(MatchResult.Cancelled.class);
        assertThat(cancel(engine, released)).isInstanceOf(MatchResult.Cancelled.class);
        assertThat(cancel(engine, failed)).isInstanceOf(MatchResult.NotFound.class);
        assertThat(cancel(engine, invalid)).isInstanceOf(MatchResult.NotFound.class);
        assertThat(cancel(engine, noTokens)).isInstanceOf(MatchResult.NotFound.class);
        assertThat(cancel(engine, unknownTokens)).isInstanceOf(MatchResult.NotFound.class);
        assertThat(cancel(engine, agreed)).isInstanceOf(MatchResult.NotFound.class);
        assertThat(cancel(engine, expired)).isInstanceOf(MatchResult.NotFound.class);
    }

    @Test
    void restoresOrdersWithoutAgreement() {
        ConsumerOrder waiting = persist(order("10.00"));
        ConsumerOrder invalid = persist(order(null));
        ConsumerOrder agreed = persist(order("10.00"));
        ProviderOffer offer = persist(offer("10.00", null, "1000"));
        persist(new Agreement(agreed, offer, OffsetDateTime.now(), null, Money.of("10"), Money.of("10")));
        ConsumerOrder expired = persist(new ConsumerOrder(Money.of("10.00"), Money.of("10.00"),
                Duration.ofSeconds(30), OffsetDateTime.now().minusMinutes(1), null, null, null));
        ConsumerOrder stillWaiting = persist(new ConsumerOrder(Money.of("10.00"), Money.of("10.00"),
                Duration.ofHours(1), OffsetDateTime.now().minusMinutes(1), null, null, null));

        MatchingEngine engine = newEngine();
        MarketLogic.restoreOrders(engine, orders, rules);

        assertThat(cancel(engine, waiting)).isInstanceOf(MatchResult.Cancelled.class);
        assertThat(cancel(engine, invalid)).isInstanceOf(MatchResult.NotFound.class);
        assertThat(cancel(engine, agreed)).isInstanceOf(MatchResult.NotFound.class);
        assertThat(cancel(engine, expired)).isInstanceOf(MatchResult.NotFound.class);
        assertThat(cancel(engine, stillWaiting)).isInstanceOf(MatchResult.Cancelled.class);
    }

    @Test
    void restoresOnlyTheOldestOrderOfAUserPerModel() {
        AppUser owner = appUserProvisioning.findOrCreate("restore-user", null, null);
        ConsumerOrder older = persist(new ConsumerOrder(Money.of("10.00"), Money.of("10.00"), null, null,
                "restore-provider", "model", owner));
        ConsumerOrder newer = persist(new ConsumerOrder(Money.of("10.00"), Money.of("10.00"), null, null,
                "restore-provider", "model", owner));

        MatchingEngine engine = newEngine();
        MarketLogic.restoreOrders(engine, orders, rules);

        assertThat(cancel(engine, older)).isInstanceOf(MatchResult.Cancelled.class);
        assertThat(cancel(engine, newer)).isInstanceOf(MatchResult.NotFound.class);
    }

    private MatchingEngine newEngine() {
        return new MatchingEngine(new OrderBook(), new LimitStrategy(rules), 100);
    }

    private static MatchResult cancel(MatchingEngine engine, ProviderOffer offer) {
        return engine.handle(new MatchingEngine.CancelOffer(offer.getId()));
    }

    private static MatchResult cancel(MatchingEngine engine, ConsumerOrder order) {
        return engine.handle(new MatchingEngine.CancelOrder(order.getId()));
    }

    private static ConsumerOrder order(String maxPrice) {
        Money value = maxPrice == null ? null : Money.of(maxPrice);
        return new ConsumerOrder(value, value, null, null, null, null, null);
    }

    private static ProviderOffer offer(String price, OffsetDateTime lastErrorAt, String availableTokens) {
        Money value = price == null ? null : Money.of(price);
        Long tokens = availableTokens == null ? null : Long.valueOf(availableTokens);
        return new ProviderOffer(value, value, null, null, null, lastErrorAt, null, tokens, tokens, null);
    }

    private <T> T persist(T entity) {
        transactionTemplate.executeWithoutResult(status -> entityManager.persist(entity));
        return entity;
    }
}