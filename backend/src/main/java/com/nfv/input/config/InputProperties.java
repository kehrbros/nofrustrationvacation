package com.nfv.input.config;

import jakarta.annotation.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Traffic sources and their polling, see Docs/traffic-feed.md#konfiguration.
 *
 * @param horizon    a source is not polled unless a planned route passes its region within this time (or one of its
 *                   incidents is active)
 * @param polling    polling of all {@code TRAFFIC_FEED} sources
 * @param processing processing of stored events into incidents
 * @param sources    settings per source, key = {@code TrafficSource.name()}; a source without entry is disabled
 */
@ConfigurationProperties("nfv.input")
public record InputProperties(@Nullable Duration horizon, @Nullable Polling polling, @Nullable Processing processing,
                              @Nullable Map<String, Source> sources) {
    public InputProperties {
        horizon = horizon == null ? Duration.ofDays(14) : horizon;
        polling = polling == null ? new Polling(false, null) : polling;
        processing = processing == null ? new Processing(false) : processing;
        sources = sources == null ? Map.of() : sources;
    }

    public @Nullable Source source(String name) {
        return sources.get(name);
    }

    /**
     * @param schedulerEnabled poll in the background; off in tests, which call {@code TrafficPolling.pollNow}
     * @param tick             how often the scheduler looks for due sources, each source has its own interval
     */
    public record Polling(boolean schedulerEnabled, @Nullable Duration tick) {
    }

    /** @param afterPoll process new events after each poll; off in tests, which call {@code processPending} */
    public record Processing(boolean afterPoll) {
    }

    /**
     * Settings of one source, unset values fall back to the defaults of the adapter.
     *
     * @param url           {@code http(s):} fetches from the source, {@code classpath:}/{@code file:} reads a JSON file
     * @param networkRegion region of the road network to locate the events in
     * @param assumedDelay  delays the source assumes without a figure, keys defined by the adapter
     */
    public record Source(boolean enabled, @Nullable String url, @Nullable String networkRegion,
                         @Nullable Duration interval, @Nullable Duration jitter, @Nullable Duration maxBackoff,
                         @Nullable Duration timeout, @Nullable Map<String, Duration> assumedDelay) {
        public Source {
            assumedDelay = assumedDelay == null ? Map.of() : assumedDelay;
        }
    }
}
