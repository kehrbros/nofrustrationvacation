package com.vacantagent.market.config;

import com.vacantagent.market.data.AgreementRepository;
import com.vacantagent.market.data.AgreementWriter;
import com.vacantagent.market.engine.MatchingEngine;
import com.vacantagent.market.logic.MarketRules;
import com.vacantagent.market.matching.LimitStrategy;
import com.vacantagent.market.matching.MatchingStrategy;
import com.vacantagent.market.matching.entities.OrderBook;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

/**
 * The only place the matching core meets Spring. Everything created here is plain Java,
 * so it can be built the same way in unit tests without a Spring context.
 */
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

    /** Restored, started and stopped by {@code MarketLogic}, which coordinates everything around matching. */
    @Bean
    public MatchingEngine matchingEngine(MatchingStrategy strategy, MarketProperties props) {
        return new MatchingEngine(new OrderBook(), strategy, props.inboxCapacity());
    }
}