package com.vacantagent.market.logic;

import com.vacantagent.agent.logic.AgentLogic;
import com.vacantagent.common.money.Money;
import com.vacantagent.market.config.MarketProperties;
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
import org.junit.jupiter.api.BeforeEach;
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
    private MarketProperties props;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private AppUserProvisioning appUserProvisioning;
    @Autowired
    private AgentLogic agentLogic;

    /** Offers reference an existing agent (foreign key). */
    private long agentId;

    @BeforeEach
    void setUp() {
        long appUserId = appUserProvisioning.findOrCreate("restore-user", null, null).getId();
        agentId = agentLogic.create(appUserId, "model", "provider", null, "sk-test-12345678").getId();
    }

    @Test
    void restoresOnlyAvailableOffers() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime longAgo = now.minusHours(1);
        ProviderOffer waiting = persist(offer("10.00", null, "1000"));
        ProviderOffer failed = persist(offer("10.00", now, "1000"));
        ProviderOffer invalid = persist(offer(null, null, "1000"));
        ProviderOffer noTokens = persist(offer("10.00", null, "0"));
        ProviderOffer unknownTokens = persist(offer("10.00", null, null));
        ProviderOffer agreed = persist(offer("10.00", null, "1000"));
        persist(new Agreement(persist(emptyOrder()), agreed, now, null, Money.of("10"), Money.of("10")));
        ProviderOffer recentlyUsed = persist(offer("10.00", null, "1000"));
        ConsumerOrder usedOrder = emptyOrder();
        usedOrder.recordSuccess(now);
        persist(new Agreement(persist(usedOrder), recentlyUsed, longAgo, null, Money.of("10"), Money.of("10")));
        ProviderOffer released = persist(offer("10.00", null, "1000"));
        persist(new Agreement(persist(emptyOrder()), released, longAgo, null, Money.of("10"), Money.of("10")));
        ProviderOffer expired = persist(new ProviderOffer(Money.of("10.00"), Money.of("10.00"),
                now.minusMinutes(1), agentId, null, null, null, null, 1000L, 1000L, null));

        MatchingEngine engine = newEngine();
        MarketLogic.restoreOffers(engine, offers, rules, now, props.agreementIdleTimeout());

        assertThat(cancel(engine, waiting)).isInstanceOf(MatchResult.Cancelled.class);
        assertThat(cancel(engine, released)).isInstanceOf(MatchResult.Cancelled.class);     // agreement idle
        assertThat(cancel(engine, failed)).isInstanceOf(MatchResult.NotFound.class);
        assertThat(cancel(engine, invalid)).isInstanceOf(MatchResult.NotFound.class);
        assertThat(cancel(engine, noTokens)).isInstanceOf(MatchResult.NotFound.class);
        assertThat(cancel(engine, unknownTokens)).isInstanceOf(MatchResult.NotFound.class);
        assertThat(cancel(engine, agreed)).isInstanceOf(MatchResult.NotFound.class);
        assertThat(cancel(engine, recentlyUsed)).isInstanceOf(MatchResult.NotFound.class);   // old agreement, recent call
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
                Duration.ofSeconds(30), OffsetDateTime.now().minusMinutes(1), null, null, null, null));
        ConsumerOrder stillWaiting = persist(new ConsumerOrder(Money.of("10.00"), Money.of("10.00"),
                Duration.ofHours(1), OffsetDateTime.now().minusMinutes(1), null, null, null, null));

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
                "restore-provider", "model", null, owner));
        ConsumerOrder newer = persist(new ConsumerOrder(Money.of("10.00"), Money.of("10.00"), null, null,
                "restore-provider", "model", null, owner));

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
        return new ConsumerOrder(value, value, null, null, null, null, null, null);
    }

    private ProviderOffer offer(String price, OffsetDateTime lastErrorAt, String availableTokens) {
        Money value = price == null ? null : Money.of(price);
        Long tokens = availableTokens == null ? null : Long.valueOf(availableTokens);
        return new ProviderOffer(value, value, null, agentId, null, null, lastErrorAt, null, tokens, tokens, null);
    }

    private <T> T persist(T entity) {
        transactionTemplate.executeWithoutResult(status -> entityManager.persist(entity));
        return entity;
    }
}