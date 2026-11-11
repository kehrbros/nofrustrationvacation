package com.nfv.input.logic;

import com.nfv.incidents.logic.IncidentReporting;
import com.nfv.input.config.InputProperties;
import com.nfv.input.data.EventRepository;
import com.nfv.input.entities.EventSource;
import com.nfv.input.entities.EventSourceType;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * Polls all enabled {@code TRAFFIC_FEED} sources, each on its own schedule ({@link PollPolicy}), and hands the result
 * to {@link FeedDiff}, see Docs/traffic-feed.md#polling-trafficpolling. The fetch runs outside a transaction; a failed
 * fetch changes nothing stored and backs off.
 */
@Component
public class TrafficPolling {
    private static final Logger LOG = LoggerFactory.getLogger(TrafficPolling.class);

    private final TrafficSources sources;
    private final FeedDiff feedDiff;
    private final IncidentReporting incidentReporting;
    private final EventRepository eventRepository;
    private final InputProperties properties;
    private final Clock clock;
    private final RandomGenerator random = RandomGenerator.getDefault();
    private final Map<String, PollSchedule> schedules = new HashMap<>();

    public TrafficPolling(TrafficSources sources, FeedDiff feedDiff, IncidentReporting incidentReporting,
                          EventRepository eventRepository, InputProperties properties, Clock clock) {
        this.sources = sources;
        this.feedDiff = feedDiff;
        this.incidentReporting = incidentReporting;
        this.eventRepository = eventRepository;
        this.properties = properties;
        this.clock = clock;
    }

    public enum Status {
        /** Fetched and compared. */
        POLLED,
        /** The source answered "not modified". */
        NOT_MODIFIED,
        /** Fetch failed, nothing changed, backing off. */
        FAILED,
        /** Source disabled. */
        SKIPPED
    }

    /** @param diff result of the comparison, {@code null} unless {@link Status#POLLED} */
    public record PollResult(String sourceName, Status status, @Nullable FeedDiff.Result diff) {
    }

    /**
     * Polls every enabled source that is due. A source is paused (looked at again after its interval) while no
     * planned route passes its region within {@code nfv.input.horizon} and none of its incidents is active. Called by
     * {@link TrafficPollingScheduler}.
     */
    public synchronized void pollDue() {
        for (TrafficSource source : sources.all()) {
            if (source.type() != EventSourceType.TRAFFIC_FEED) {
                continue;
            }
            PollSchedule schedule = schedule(source);
            if (schedule.isDue(clock.instant()) && sources.isEnabled(source)) {
                try {
                    if (!isNeeded(source)) {
                        LOG.debug("Traffic source {} paused, nobody travels in region {}", source.name(),
                                source.networkRegion());
                        schedule.paused(clock.instant());
                        continue;
                    }
                    poll(source, null, schedule);
                } catch (RuntimeException e) {
                    // Storing failed (not the source): try again on the next regular poll
                    LOG.error("Poll of traffic source {} failed", source.name(), e);
                    schedule.failed(clock.instant());
                }
            }
        }
    }

    /** Polls the source now, regardless of its schedule and pause (tests, operations). */
    public PollResult pollNow(String sourceName) {
        return pollNow(sourceName, null);
    }

    /**
     * Polls the source now from another address than the configured one, e.g. another fixture file in tests.
     *
     * @param location {@code http(s):}, {@code classpath:} or {@code file:} address, {@code null} for the configured
     */
    public synchronized PollResult pollNow(String sourceName, @Nullable String location) {
        TrafficSource source = sources.find(sourceName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown traffic source " + sourceName));
        if (source.type() != EventSourceType.TRAFFIC_FEED || !sources.isEnabled(source)) {
            return new PollResult(sourceName, Status.SKIPPED, null);
        }
        return poll(source, location, schedule(source));
    }

    private PollResult poll(TrafficSource source, @Nullable String location, PollSchedule schedule) {
        EventSource eventSource = sources.eventSource(source);
        OffsetDateTime now = OffsetDateTime.now(clock);
        SourceSnapshot snapshot;
        try {
            snapshot = source.fetch(new FetchContext(eventSource.getEtag(), eventSource.getLastModified(), now,
                    location));
        } catch (SourceUnavailableException e) {
            schedule.failed(clock.instant());
            LOG.warn("Traffic source {} unavailable ({} failures in a row, next poll at {}): {}", source.name(),
                    schedule.failures(), schedule.nextPollAt(), e.getMessage());
            return new PollResult(source.name(), Status.FAILED, null);
        }
        FeedDiff.Result diff = feedDiff.apply(source.name(), source.pollPolicy(), snapshot, now);
        schedule.succeeded(clock.instant());
        if (snapshot.notModified()) {
            return new PollResult(source.name(), Status.NOT_MODIFIED, null);
        }
        LOG.debug("Polled traffic source {}: {}", source.name(), diff);
        return new PollResult(source.name(), Status.POLLED, diff);
    }

    /** A planned route passes the region of the source within the horizon, or an incident of the source is active. */
    public boolean isNeeded(TrafficSource source) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return incidentReporting.hasPassagesInRegion(source.networkRegion(), now, now.plus(properties.horizon()))
                || incidentReporting.anyActive(
                eventRepository.findIncidentIdsBySourceId(sources.eventSource(source).getId()));
    }

    private PollSchedule schedule(TrafficSource source) {
        return schedules.computeIfAbsent(source.name(),
                name -> new PollSchedule(source.pollPolicy(), random, clock.instant()));
    }
}
