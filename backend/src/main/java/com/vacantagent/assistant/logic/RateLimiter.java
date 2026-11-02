package com.vacantagent.assistant.logic;

import com.vacantagent.assistant.config.AssistantProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Requests per user within a sliding hour, in memory (one backend replica, a restart resets it). */
@Component
@RequiredArgsConstructor
class RateLimiter {
    private static final Duration WINDOW = Duration.ofHours(1);

    private final AssistantProperties properties;
    private final Clock clock;
    private final Map<Long, Deque<Instant>> requests = new ConcurrentHashMap<>();

    void acquire(long appUserId) {
        Instant now = clock.instant();
        Deque<Instant> recent = requests.computeIfAbsent(appUserId, id -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(WINDOW))) {
                recent.pollFirst();
            }
            if (recent.size() >= properties.requestsPerHour()) {
                throw new AssistantRateLimitedException();
            }
            recent.addLast(now);
        }
    }
}
