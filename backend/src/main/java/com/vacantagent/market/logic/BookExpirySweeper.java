package com.vacantagent.market.logic;

import com.vacantagent.market.config.MarketProperties;
import com.vacantagent.market.engine.MatchingEngine;
import com.vacantagent.market.matching.MatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes expired offers and orders from the book every {@link MarketProperties#expirySweepInterval}. Matching
 * already skips them, so this only frees memory and keeps the index small; the interval is not about correctness.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookExpirySweeper {
    private final MatchingEngine engine;
    private final MarketRules rules;

    @Scheduled(fixedDelayString = "${vacantagent.market.expiry-sweep-interval}")
    public void sweep() {
        if (!engine.isRunning()) return;
        engine.submit(new MatchingEngine.ExpireEntries(rules.nowMillis())).whenComplete((result, e) -> {
            if (e != null) log.warn("Removing expired entries from the book failed", e);
            else if (result instanceof MatchResult.Expired x && (x.offers() > 0 || x.orders() > 0)) {
                log.info("Removed {} expired offers and {} expired orders from the book", x.offers(), x.orders());
            }
        });
    }
}