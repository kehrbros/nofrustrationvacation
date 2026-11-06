package com.nfv.assistant.logic;

import com.nfv.assistant.config.AssistantProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Requests within a sliding hour, in memory (one backend replica, a restart resets it): per user, and one quota of its
 * own for background requests without a user.
 */
@Component
@RequiredArgsConstructor
class RateLimiter {
    private static final Duration WINDOW = Duration.ofHours(1);
    /** Key of the system quota, user ids are positive. */
    private static final long SYSTEM = -1;

    private final AssistantProperties properties;
    private final Clock clock;
    private final Map<Long, Deque<Instant>> requests = new ConcurrentHashMap<>();

    void acquire(long appUserId) {
        acquire(appUserId, properties.requestsPerHour());
    }

    void acquireSystem() {
        acquire(SYSTEM, properties.systemRequestsPerHour());
    }

    private void acquire(long key, int limit) {
        Instant now = clock.instant();
        Deque<Instant> recent = requests.computeIfAbsent(key, id -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(WINDOW))) {
                recent.pollFirst();
            }
            if (recent.size() >= limit) {
                throw new AssistantRateLimitedException();
            }
            recent.addLast(now);
        }
    }
}
