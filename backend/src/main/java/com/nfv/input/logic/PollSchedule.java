package com.nfv.input.logic;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

/**
 * When a source is polled next: {@code interval} after a success, growing backoff after failures, each plus a random
 * jitter. Kept in memory (one replica, see Docs/deployment.md).
 */
class PollSchedule {
    private final PollPolicy policy;
    private final RandomGenerator random;
    private Instant nextPollAt;
    private int failures;

    /** The first poll is due right after the start, plus jitter. */
    PollSchedule(PollPolicy policy, RandomGenerator random, Instant now) {
        this.policy = policy;
        this.random = random;
        this.nextPollAt = now.plus(jitter());
    }

    boolean isDue(Instant now) {
        return !now.isBefore(nextPollAt);
    }

    void succeeded(Instant now) {
        failures = 0;
        nextPollAt = now.plus(policy.interval()).plus(jitter());
    }

    void failed(Instant now) {
        failures++;
        nextPollAt = now.plus(policy.backoff(failures)).plus(jitter());
    }

    /** Not polled because nobody travels in the region: look again after the interval, failures stay. */
    void paused(Instant now) {
        nextPollAt = now.plus(policy.interval());
    }

    Instant nextPollAt() {
        return nextPollAt;
    }

    int failures() {
        return failures;
    }

    private Duration jitter() {
        long max = policy.jitter().toMillis();
        return max <= 0 ? Duration.ZERO : Duration.ofMillis(random.nextLong(max + 1));
    }
}
