package com.nfv.input.logic;

import com.nfv.input.data.EventRepository;
import com.nfv.input.data.EventSourceRepository;
import com.nfv.input.data.SourceSubjectRepository;
import com.nfv.input.entities.Event;
import com.nfv.input.entities.EventSource;
import com.nfv.input.entities.SourceSubject;
import com.nfv.input.entities.TrafficEvent;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares a fetched {@link SourceSnapshot} with the last known version of each event of the source and stores new
 * versions as {@link TrafficEvent}s, see Docs/traffic-feed.md#abgleich-feeddiff.
 * <ul>
 *     <li>new or changed (other version): one event {@code <subjectId>@<version>}</li>
 *     <li>unchanged: nothing stored</li>
 *     <li>missing from a complete snapshot: after {@code clearAfterMissing} polls one event
 *     {@code <subjectId>@cleared@<time>} with the last known payload</li>
 *     <li>more than {@code maxClearRatio} of the known events missing at once (from {@code minKnownForRatio} known
 *     events on): the poll is not counted as missing for anyone, warning in the log</li>
 * </ul>
 */
@Component
public class FeedDiff {
    private static final Logger LOG = LoggerFactory.getLogger(FeedDiff.class);
    /** Part of the external id of an event that says the event disappeared from the feed. */
    static final String CLEARED = "@cleared@";

    private final EventSourceRepository eventSourceRepository;
    private final SourceSubjectRepository subjectRepository;
    private final EventRepository eventRepository;
    private final ApplicationEventPublisher events;

    public FeedDiff(EventSourceRepository eventSourceRepository, SourceSubjectRepository subjectRepository,
                    EventRepository eventRepository, ApplicationEventPublisher events) {
        this.eventSourceRepository = eventSourceRepository;
        this.subjectRepository = subjectRepository;
        this.eventRepository = eventRepository;
        this.events = events;
    }

    /** Whether the event says that the event at the source disappeared from the feed. */
    public static boolean isCleared(Event event) {
        return event.getExternalId() != null && event.getExternalId().contains(CLEARED);
    }

    /** Published on every successful poll, after the commit pending events are processed. */
    public record SourcePolled(String sourceName) {
    }

    /**
     * @param created        events seen for the first time
     * @param changed        events with a new version (or back after being cleared)
     * @param unchanged      events with the known version
     * @param missing        known, not cleared events missing from a complete snapshot
     * @param cleared        events cleared in this poll
     * @param clearBlocked   too many events missing at once, nothing counted as missing
     */
    public record Result(int created, int changed, int unchanged, int missing, int cleared, boolean clearBlocked) {
        static final Result NOT_MODIFIED = new Result(0, 0, 0, 0, 0, false);
    }

    @Transactional
    public Result apply(String sourceName, PollPolicy policy, SourceSnapshot snapshot, OffsetDateTime now) {
        EventSource source = eventSourceRepository.findByName(sourceName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown event source " + sourceName));
        source.polled(now, snapshot.etag(), snapshot.lastModified(), snapshot.notModified());
        // also without new events: events still waiting (e.g. network not loaded) are processed again
        events.publishEvent(new SourcePolled(sourceName));
        if (snapshot.notModified()) {
            return Result.NOT_MODIFIED;
        }

        Map<String, SourceSubject> known = subjectRepository.findBySourceId(source.getId()).stream()
                .collect(Collectors.toMap(SourceSubject::getSubjectId, Function.identity()));
        Set<String> inSnapshot = new HashSet<>();
        int created = 0;
        int changed = 0;
        int unchanged = 0;
        for (SourceItem item : snapshot.items()) {
            if (!inSnapshot.add(item.subjectId())) {
                LOG.warn("Source {} delivered event {} twice, keeping the first", sourceName, item.subjectId());
                continue;
            }
            String hash = item.messageHash();
            SourceSubject subject = known.get(item.subjectId());
            if (subject == null) {
                subjectRepository.save(new SourceSubject(source, item.subjectId(), item.version(), hash, now));
                store(source, item, hash, now);
                created++;
            } else if (!subject.getVersion().equals(item.version()) || subject.isCleared()) {
                subject.seen(item.version(), hash, now);
                store(source, item, hash, now);
                changed++;
            } else {
                subject.seen(item.version(), hash, now);
                unchanged++;
            }
        }

        if (!snapshot.complete()) {
            return new Result(created, changed, unchanged, 0, 0, false);
        }
        List<SourceSubject> missing = known.values().stream()
                .filter(subject -> !subject.isCleared() && !inSnapshot.contains(subject.getSubjectId()))
                .toList();
        long active = known.values().stream().filter(subject -> !subject.isCleared()).count();
        if (!missing.isEmpty() && active >= policy.minKnownForRatio()
                && missing.size() > policy.maxClearRatio() * active) {
            LOG.warn("Source {}: {} of {} known events missing at once, not clearing any", sourceName,
                    missing.size(), active);
            return new Result(created, changed, unchanged, missing.size(), 0, true);
        }
        List<SourceSubject> toClear = new ArrayList<>();
        for (SourceSubject subject : missing) {
            if (subject.missing() >= policy.clearAfterMissing()) {
                toClear.add(subject);
            }
        }
        for (SourceSubject subject : toClear) {
            subject.cleared(now);
            String lastPayload = eventRepository
                    .findBySourceIdAndExternalId(source.getId(), externalId(subject.getSubjectId(), subject.getVersion()))
                    .map(Event::getPayload)
                    .orElse("{}");
            eventRepository.save(new TrafficEvent(source,
                    subject.getSubjectId() + CLEARED + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now),
                    subject.getSubjectId(), subject.getMessageHash(), lastPayload, now));
        }
        if (!toClear.isEmpty()) {
            LOG.info("Source {}: cleared {} events", sourceName, toClear.size());
        }
        return new Result(created, changed, unchanged, missing.size(), toClear.size(), false);
    }

    private void store(EventSource source, SourceItem item, @Nullable String hash, OffsetDateTime now) {
        String externalId = externalId(item.subjectId(), item.version());
        if (eventRepository.findBySourceIdAndExternalId(source.getId(), externalId).isPresent()) {
            // Same version back after being cleared: keep the external id unique
            externalId = externalId + "@reappeared@" + DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now);
        }
        eventRepository.save(new TrafficEvent(source, externalId, item.subjectId(), hash, item.payload(), now));
    }

    static String externalId(String subjectId, String version) {
        return subjectId + "@" + version;
    }
}
