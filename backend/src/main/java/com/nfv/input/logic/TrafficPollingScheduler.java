package com.nfv.input.logic;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Looks for due traffic sources every {@code nfv.input.polling.tick}; each source has its own interval
 * ({@link PollPolicy}). Off in tests, they call {@link TrafficPolling#pollNow}.
 */
@Component
@ConditionalOnProperty(name = "nfv.input.polling.scheduler-enabled", havingValue = "true")
public class TrafficPollingScheduler {
    private final TrafficPolling polling;

    public TrafficPollingScheduler(TrafficPolling polling) {
        this.polling = polling;
    }

    @Scheduled(fixedDelayString = "${nfv.input.polling.tick:30s}", initialDelayString = "${nfv.input.polling.tick:30s}")
    public void poll() {
        polling.pollDue();
    }
}
