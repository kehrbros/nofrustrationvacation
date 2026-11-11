package com.nfv.bookings.logic;

import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Profiles of LiteAPI hotels (stars, kind, description, facilities) for the comparison by the assistant, see
 * Docs/accommodation-alternatives.md#roles-assistant-and-liteapi. Master data barely changes, so they are kept in
 * memory for a day; a search loads the missing ones in parallel. A hotel whose profile cannot be loaded is left out
 * of the map, the comparison then works with the facts of the offer.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class HotelProfiles {
    private static final Duration TTL = Duration.ofHours(24);
    private static final int MAX_ENTRIES = 5_000;
    private static final int PARALLEL = 6;

    private final LiteApi liteApi;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final Semaphore requests = new Semaphore(PARALLEL);

    private record Cached(LiteApi.HotelProfile profile, Instant until) {
    }

    Optional<LiteApi.HotelProfile> get(String hotelId) {
        return Optional.ofNullable(all(List.of(hotelId)).get(hotelId));
    }

    /** Profiles by hotel id, in the order of the ids; missing: not loadable. */
    Map<String, LiteApi.HotelProfile> all(Collection<String> hotelIds) {
        Instant now = clock.instant();
        Map<String, LiteApi.HotelProfile> profiles = new LinkedHashMap<>();
        Map<String, Future<LiteApi.HotelProfile>> loading = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (String hotelId : hotelIds) {
                Cached cached = cache.get(hotelId);
                if (cached != null && cached.until().isAfter(now)) {
                    profiles.put(hotelId, cached.profile());
                } else if (!loading.containsKey(hotelId)) {
                    loading.put(hotelId, executor.submit(() -> load(hotelId)));
                }
            }
            for (Map.Entry<String, Future<LiteApi.HotelProfile>> entry : loading.entrySet()) {
                LiteApi.HotelProfile profile = result(entry.getKey(), entry.getValue());
                if (profile != null) {
                    profiles.put(entry.getKey(), profile);
                    remember(profile, now);
                }
            }
        }
        return profiles;
    }

    private LiteApi.HotelProfile load(String hotelId) throws InterruptedException {
        requests.acquire();
        try {
            return liteApi.profile(hotelId);
        } finally {
            requests.release();
        }
    }

    private static @Nullable LiteApi.HotelProfile result(String hotelId, Future<LiteApi.HotelProfile> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            log.info("Profile of hotel {} not loaded: {}", hotelId, e.getCause().getMessage());
            return null;
        }
    }

    private void remember(LiteApi.HotelProfile profile, Instant now) {
        if (cache.size() >= MAX_ENTRIES) {
            cache.values().removeIf(cached -> !cached.until().isAfter(now));
            if (cache.size() >= MAX_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(profile.hotelId(), new Cached(profile, now.plus(TTL)));
    }
}