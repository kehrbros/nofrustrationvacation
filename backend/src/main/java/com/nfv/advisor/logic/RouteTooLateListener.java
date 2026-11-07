package com.nfv.advisor.logic;

import com.nfv.advisor.config.AdvisorProperties;
import com.nfv.incidents.logic.RouteTooLate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Starts a run for a route that is too late, see Docs/advisor.md#automatisch-nur-too_late. After the commit and on a
 * thread of its own: routing and the model must not run inside the transaction of the assessment, and on a rollback
 * no run starts.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RouteTooLateListener {
    private final AlternativeProposals proposals;
    private final AdvisorProperties properties;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRouteTooLate(RouteTooLate event) {
        if (properties.autoEnabled()) {
            Thread.ofVirtual().name("advisor-auto-run").start(() -> start(event));
        }
    }

    private void start(RouteTooLate event) {
        try {
            proposals.startAutomatic(event);
        } catch (RuntimeException e) {
            log.warn("Automatic advisor run for route {} not started", event.routeId(), e);
        }
    }
}
