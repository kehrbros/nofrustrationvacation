package com.nfv.input.logic;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;

/** Schedule of one source: interval, backoff 10 → 20 → 30 min for NZTA, jitter. */
class PollScheduleTest {
    private static final PollPolicy NZTA = new PollPolicy(Duration.ofMinutes(5), Duration.ofSeconds(30),
            Duration.ofMinutes(30), Duration.ofSeconds(20), 2, 0.5, 10);
    private static final Instant T0 = Instant.parse("2026-11-03T09:15:00Z");

    @Test
    void backoffDoublesFromTwiceTheIntervalUpToTheMaximum() {
        assertThat(NZTA.backoff(1)).isEqualTo(Duration.ofMinutes(10));
        assertThat(NZTA.backoff(2)).isEqualTo(Duration.ofMinutes(20));
        assertThat(NZTA.backoff(3)).isEqualTo(Duration.ofMinutes(30));
        assertThat(NZTA.backoff(50)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void nextPollAfterIntervalOrBackoffPlusJitter() {
        PollSchedule schedule = new PollSchedule(NZTA, fixedRandom(10_000), T0);
        assertThat(schedule.nextPollAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(schedule.isDue(T0)).isFalse();
        assertThat(schedule.isDue(T0.plusSeconds(10))).isTrue();

        schedule.failed(T0);
        schedule.failed(T0);
        assertThat(schedule.failures()).isEqualTo(2);
        assertThat(schedule.nextPollAt()).isEqualTo(T0.plus(Duration.ofMinutes(20)).plusSeconds(10));

        schedule.succeeded(T0);
        assertThat(schedule.failures()).isZero();
        assertThat(schedule.nextPollAt()).isEqualTo(T0.plus(Duration.ofMinutes(5)).plusSeconds(10));
    }

    @Test
    void jitterStaysWithinPolicy() {
        PollSchedule schedule = new PollSchedule(NZTA, RandomGenerator.getDefault(), T0);
        for (int i = 0; i < 100; i++) {
            schedule.succeeded(T0);
            assertThat(schedule.nextPollAt()).isBetween(T0.plus(Duration.ofMinutes(5)),
                    T0.plus(Duration.ofMinutes(5)).plusSeconds(30));
        }
    }

    private static RandomGenerator fixedRandom(long millis) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return millis;
            }

            @Override
            public long nextLong(long bound) {
                return millis;
            }
        };
    }
}
