package com.nfv.bookings.logic;

import com.nfv.bookings.config.AlternativesProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Searches at LiteAPI per user within a sliding hour, in memory like the assistant's limit. */
@Component
@RequiredArgsConstructor
class SearchLimiter {
    private static final Duration WINDOW = Duration.ofHours(1);

    private final AlternativesProperties properties;
    private final Clock clock;
    private final Map<Long, Deque<Instant>> searches = new ConcurrentHashMap<>();

    void acquire(long appUserId) {
        Instant now = clock.instant();
        Deque<Instant> recent = searches.computeIfAbsent(appUserId, id -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && recent.peekFirst().isBefore(now.minus(WINDOW))) {
                recent.pollFirst();
            }
            if (recent.size() >= properties.searchesPerHour()) {
                throw new SearchRateLimitedException();
            }
            recent.addLast(now);
        }
    }
}
