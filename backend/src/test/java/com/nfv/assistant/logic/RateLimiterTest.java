package com.nfv.assistant.logic;

import com.nfv.assistant.config.AssistantProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The system quota is separate from the limit per user and frees up after an hour. */
class RateLimiterTest {
    private static final AssistantProperties PROPERTIES = new AssistantProperties("key", "url", "model", "image", "low",
            Duration.ofSeconds(1), 1, 2);

    private Instant now = Instant.parse("2026-11-05T00:00:00Z");
    private final RateLimiter limiter = new RateLimiter(PROPERTIES, new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });

    @Test
    void systemQuotaIsItsOwn() {
        limiter.acquire(1);
        assertThatThrownBy(() -> limiter.acquire(1)).isInstanceOf(AssistantRateLimitedException.class);

        limiter.acquireSystem();
        limiter.acquireSystem();
        assertThatThrownBy(limiter::acquireSystem).isInstanceOf(AssistantRateLimitedException.class);
        assertThatCode(() -> limiter.acquire(2)).as("other users go on").doesNotThrowAnyException();

        now = now.plus(Duration.ofMinutes(61));
        assertThatCode(limiter::acquireSystem).doesNotThrowAnyException();
    }
}
