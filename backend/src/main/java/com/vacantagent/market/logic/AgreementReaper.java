package com.vacantagent.market.logic;

import com.vacantagent.market.config.MarketProperties;
import com.vacantagent.market.data.AgreementRepository;
import com.vacantagent.market.data.ConsumerOrderRepository;
import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.entities.ProviderOffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Sends the offers of agreements that became idle (no call for {@link MarketProperties#agreementIdleTimeout}, see
 * {@code Agreement#isIdleSince}) back into the market. Nothing is persisted: being idle follows from time alone.
 *
 * <p>Each agreement becomes idle at one fixed moment, as calls are refused from then on. Every sweep releases the
 * agreements that became idle between the previous sweep and now, so the windows join without gaps or overlaps and
 * each offer is released exactly once. The first window starts at {@link IMarketLogic#restoredAt}: offers of
 * agreements idle before that came back with the restore, also those that became idle while the application was down.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgreementReaper {
    private final AgreementRepository agreements;
    private final ConsumerOrderRepository orders;
    private final IMarketLogic market;
    private final MarketProperties props;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** End of the last released window; only touched by the scheduler thread. */
    private OffsetDateTime releasedUpTo;

    @Scheduled(fixedDelayString = "${vacantagent.market.agreement-sweep-interval}")
    public void sweepScheduled() {
        sweep();
    }

    /** @return number of offers released */
    int sweep() {
        if (releasedUpTo == null) releasedUpTo = market.restoredAt();
        if (releasedUpTo == null) return 0;                     // market not started
        OffsetDateTime now = OffsetDateTime.now(clock);
        Duration timeout = props.agreementIdleTimeout();
        OffsetDateTime cutoff = now.minus(timeout);
        int released = 0;
        for (long id : agreements.findIdleIds(now, releasedUpTo.minus(timeout), cutoff)) {
            try {
                ProviderOffer offer = transactionTemplate.execute(status -> idleOffer(id, cutoff));
                if (offer == null) continue;
                released++;
                market.resubmitOffer(offer).whenComplete((result, e) -> {
                    if (e == null) log.info("Agreement {} unused, offer {} back in the market: {}", id, offer.getId(), result);
                    else log.warn("Agreement {} unused, resubmitting offer {} failed", id, offer.getId(), e);
                });
            } catch (RuntimeException e) {
                log.warn("Releasing unused agreement {} failed", id, e);
            }
        }
        releasedUpTo = now;
        return released;
    }

    /** Re-checks under the order's row lock, as the consumer may have called in the meantime. */
    private ProviderOffer idleOffer(long id, OffsetDateTime cutoff) {
        if (orders.findForUpdateByAgreementId(id).isEmpty()) return null;
        Agreement agreement = agreements.findById(id).orElseThrow();      // shares the locked, fresh order
        return agreement.isIdleSince(cutoff) ? agreement.getProviderOffer() : null;
    }
}