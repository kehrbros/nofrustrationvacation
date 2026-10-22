package com.vacantagent.market.config;

import com.vacantagent.market.data.AgreementRepository;
import com.vacantagent.market.data.AgreementWriter;
import com.vacantagent.market.data.ConsumerOrderRepository;
import com.vacantagent.market.data.ProviderOfferRepository;
import com.vacantagent.market.engine.MatchingEngine;
import com.vacantagent.market.logic.MarketRules;
import com.vacantagent.market.matching.LimitStrategy;
import com.vacantagent.market.matching.MatchingStrategy;
import com.vacantagent.market.matching.entities.BookOffer;
import com.vacantagent.market.matching.entities.BookOrder;
import com.vacantagent.market.matching.entities.OrderBook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * The only place the matching core meets Spring. Everything created here is plain Java,
 * so it can be built the same way in unit tests without a Spring context.
 */
@Slf4j
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MarketProperties.class)
public class MarketConfig {

    @Bean
    public MarketRules marketRules(MarketProperties props, Clock clock) {
        return new MarketRules(props.maxPrice(), clock);
    }

    /** Swap this bean to change how matching works; the engine only knows the interface. */
    @Bean
    public MatchingStrategy matchingStrategy(MarketRules rules) {
        return new LimitStrategy(rules);
    }

    @Bean(initMethod = "start", destroyMethod = "stop")
    public AgreementWriter agreementWriter(AgreementRepository repository, MarketProperties props) {
        return new AgreementWriter(repository, props.writerBatchSize());
    }

    /**
     * Depends on the writer, so Spring stops the engine first and the writer can flush the last agreements.
     * Waiting offers and orders are reloaded before start runs.
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public MatchingEngine matchingEngine(MatchingStrategy strategy, AgreementWriter writer, MarketProperties props,
                                         ProviderOfferRepository offers, ConsumerOrderRepository orders,
                                         MarketRules rules) {
        MatchingEngine engine = new MatchingEngine(new OrderBook(), strategy, writer::enqueue, props.inboxCapacity());
        restoreOffers(engine, offers, rules);
        restoreOrders(engine, orders, rules);
        return engine;
    }

    /**
     * Loads every available offer (tokens left, no open agreement), oldest first. Offers that
     * have expired, or that {@link MarketRules#isValid} refuses (provider failed, invalid prices), are skipped,
     * as the strategy would reject them.
     */
    static void restoreOffers(MatchingEngine engine, ProviderOfferRepository offers, MarketRules rules) {
        long now = rules.nowMillis();
        int restored = 0;
        int expired = 0;
        for (var offer : offers.findAllAvailable()) {
            BookOffer bookOffer = BookOffer.from(offer);
            if (rules.isExpired(bookOffer, now)) {
                expired++;
                continue;
            }
            if (!rules.isValid(bookOffer)) {
                log.warn("Not restoring offer {}: violates market rules", offer.getId());
                continue;
            }
            engine.restoreOffer(bookOffer);
            restored++;
        }
        log.info("Restored {} waiting offers, skipped {} expired", restored, expired);
    }

    /**
     * Loads every order without an agreement, oldest first. Orders whose wait time is over, or that
     * {@link MarketRules#isValid} refuses (invalid prices), are skipped, as the strategy would reject them.
     */
    static void restoreOrders(MatchingEngine engine, ConsumerOrderRepository orders, MarketRules rules) {
        long now = rules.nowMillis();
        int restored = 0;
        int expired = 0;
        for (var order : orders.findAllWithoutAgreement()) {
            BookOrder bookOrder = BookOrder.from(order);
            if (rules.isExpired(bookOrder, now)) {
                expired++;
                continue;
            }
            if (!rules.isValid(bookOrder)) {
                log.warn("Not restoring order {}: violates market rules", order.getId());
                continue;
            }
            engine.restoreOrder(bookOrder);
            restored++;
        }
        log.info("Restored {} waiting orders, skipped {} expired", restored, expired);
    }
}