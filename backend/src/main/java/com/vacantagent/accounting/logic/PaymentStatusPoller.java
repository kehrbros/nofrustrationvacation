package com.vacantagent.accounting.logic;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls PayPal for top-ups whose capture is pending and payouts that are not final yet. Replaceable by PayPal
 * webhooks later, see Docs/accounting.md.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "vacantagent.accounting.status-check-enabled", havingValue = "true")
public class PaymentStatusPoller {
    private final TopUpLogic topUpLogic;
    private final PayoutLogic payoutLogic;

    @Scheduled(fixedDelayString = "${vacantagent.accounting.status-check-interval}",
            initialDelayString = "${vacantagent.accounting.status-check-interval}")
    public void poll() {
        topUpLogic.checkPending();
        payoutLogic.checkSubmitted();
    }
}
