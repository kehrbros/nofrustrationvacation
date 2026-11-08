package com.nfv.incidents.logic;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs {@link ResolvedIncidents} every {@code nfv.incidents.resolved-check.interval}. Off in tests. */
@Component
@ConditionalOnProperty(name = "nfv.incidents.resolved-check.enabled", havingValue = "true")
public class ResolvedIncidentsScheduler {
    private final ResolvedIncidents resolvedIncidents;

    public ResolvedIncidentsScheduler(ResolvedIncidents resolvedIncidents) {
        this.resolvedIncidents = resolvedIncidents;
    }

    @Scheduled(fixedDelayString = "${nfv.incidents.resolved-check.interval:5m}",
            initialDelayString = "${nfv.incidents.resolved-check.interval:5m}")
    public void check() {
        resolvedIncidents.markResolved();
    }
}
