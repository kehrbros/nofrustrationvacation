package com.vacantagent.market.logic;

import com.vacantagent.market.config.MarketProperties;
import com.vacantagent.market.data.AgreementRepository;
import com.vacantagent.market.entities.Agreement;
import com.vacantagent.market.entities.ProviderOffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Closes agreements the consumer has not called for {@link MarketProperties#agreementIdleTimeout} and sends
 * their offers back into the market. {@code lastErrorAt} is persisted before the offer is resubmitted, so if
 * the resubmit is lost (crash, engine stopped) the offer is still restored on the next startup.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgreementReaper {
    private final AgreementRepository agreements;
    private final IMarketLogic market;
    private final MarketProperties props;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${vacantagent.market.agreement-sweep-interval}")
    public void sweepScheduled() {
        sweep();
    }

    /** @return number of agreements closed */
    int sweep() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime cutoff = now.minus(props.agreementIdleTimeout());
        int closed = 0;
        for (long id : agreements.findIdleIds(now, cutoff)) {
            try {
                ProviderOffer offer = transactionTemplate.execute(status -> close(id, now, cutoff));
                if (offer == null) continue;
                closed++;
                market.resubmitOffer(offer).whenComplete((result, e) -> {
                    if (e == null) log.info("Agreement {} unused, offer {} back in the market: {}", id, offer.getId(), result);
                    else log.warn("Agreement {} unused, resubmitting offer {} failed", id, offer.getId(), e);
                });
            } catch (RuntimeException e) {
                log.warn("Closing unused agreement {} failed", id, e);
            }
        }
        return closed;
    }

    /** Re-checks under a row lock, as the consumer may have called in the meantime. */
    private ProviderOffer close(long id, OffsetDateTime now, OffsetDateTime cutoff) {
        Agreement agreement = agreements.findForUpdate(id).orElse(null);
        if (agreement == null || agreement.getLastErrorAt() != null || !agreement.isIdleSince(cutoff)) return null;
        agreement.markError(now);
        return agreement.getProviderOffer();
    }
}