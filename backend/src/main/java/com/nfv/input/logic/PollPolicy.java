package com.nfv.input.logic;

import java.time.Duration;

/**
 * How a {@code TRAFFIC_FEED} source is polled and when missing events are cleared.
 *
 * @param interval           time between two successful polls
 * @param jitter             random delay added to each poll (0 to jitter)
 * @param maxBackoff         longest wait after failures; the wait doubles from twice the interval up to this
 * @param timeout            connect and read timeout of one request
 * @param clearAfterMissing  successive complete polls without an event until it is cleared
 * @param maxClearRatio      share of the known events that may be missing in one poll; more is treated as a broken
 *                           response and clears nothing
 * @param minKnownForRatio   {@code maxClearRatio} only applies from this many known events on, so a source with
 *                           few events can still clear all of them
 */
public record PollPolicy(Duration interval, Duration jitter, Duration maxBackoff, Duration timeout,
                         int clearAfterMissing, double maxClearRatio, int minKnownForRatio) {
    /** For sources that are not polled ({@code WEBHOOK}). */
    public static final PollPolicy NONE = new PollPolicy(Duration.ZERO, Duration.ZERO, Duration.ZERO, Duration.ZERO,
            0, 0, 0);

    public PollPolicy {
        if (clearAfterMissing < 0 || maxClearRatio < 0 || maxClearRatio > 1 || minKnownForRatio < 0) {
            throw new IllegalArgumentException("Invalid poll policy");
        }
    }

    /** Wait after {@code failures} successive failed polls (at least 1): 2×, 4×, … the interval, at most maxBackoff. */
    public Duration backoff(int failures) {
        Duration wait = interval;
        for (int i = 0; i < failures && wait.compareTo(maxBackoff) < 0; i++) {
            wait = wait.multipliedBy(2);
        }
        return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
    }
}
