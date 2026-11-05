package com.nfv.input;

import com.nfv.input.data.EventRepository;
import com.nfv.input.data.EventSourceRepository;
import com.nfv.input.entities.Event;
import com.nfv.input.entities.EventSource;
import com.nfv.input.entities.EventSourceType;
import com.nfv.input.logic.FeedDiff;
import com.nfv.input.logic.PollPolicy;
import com.nfv.input.logic.SourceItem;
import com.nfv.input.logic.SourceSnapshot;
import com.nfv.input.logic.TrafficPolling;
import com.nfv.input.logic.TrafficPolling.PollResult;
import com.nfv.input.logic.TrafficPolling.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Polling and feed comparison: NZTA sample and derived files via {@link TrafficPolling#pollNow}, special cases with
 * hand-made snapshots directly on {@link FeedDiff}. See Docs/traffic-feed.md#abgleich-feeddiff.
 */
@SpringBootTest
class FeedDiffTest {
    private static final String NZTA = "nz-nzta";
    private static final String FIXTURES = "classpath:sources/nz-nzta/";
    private static final PollPolicy POLICY = new PollPolicy(Duration.ofMinutes(5), Duration.ZERO,
            Duration.ofMinutes(30), Duration.ofSeconds(20), 2, 0.5, 10);
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-03T22:15:00+13:00");

    @Autowired
    private TrafficPolling polling;
    @Autowired
    private FeedDiff feedDiff;
    @Autowired
    private EventSourceRepository eventSourceRepository;
    @Autowired
    private EventRepository eventRepository;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetNzta() {
        jdbc.update("DELETE FROM input_event WHERE source_id IN (SELECT id FROM event_source WHERE name = ?)", NZTA);
        jdbc.update("DELETE FROM source_subject WHERE source_id IN (SELECT id FROM event_source WHERE name = ?)", NZTA);
        jdbc.update("UPDATE event_source SET last_success_at = NULL, etag = NULL, last_modified = NULL, enabled = TRUE"
                + " WHERE name = ?", NZTA);
    }

    @Test
    void firstPollStoresEveryEventSecondNothing() {
        PollResult first = polling.pollNow(NZTA);
        PollResult second = polling.pollNow(NZTA);

        assertThat(first.status()).isEqualTo(Status.POLLED);
        assertThat(first.diff()).isEqualTo(new FeedDiff.Result(197, 0, 0, 0, 0, false));
        assertThat(second.diff()).isEqualTo(new FeedDiff.Result(0, 0, 197, 0, 0, false));
        List<Event> events = nztaEvents();
        assertThat(events).hasSize(197);
        Event tunnel = event("566003@2026-11-03T21:00:06.200+13:00");
        assertThat(tunnel.getSubjectId()).isEqualTo("566003");
        assertThat(tunnel.getMessageHash()).hasSize(64);
        assertThat(tunnel.getPayload()).contains("TERRACE TUNNEL START");
        assertThat(tunnel.getProcessedAt()).isNull();
        assertThat(nzta().getLastSuccessAt()).isNotNull();
    }

    @Test
    void changedTextIsANewVersionWithNewHash() {
        polling.pollNow(NZTA);
        String hash = event("566003@2026-11-03T21:00:06.200+13:00").getMessageHash();

        PollResult changed = polling.pollNow(NZTA, FIXTURES + "events-changed-text.json");

        assertThat(changed.diff()).isEqualTo(new FeedDiff.Result(0, 1, 196, 0, 0, false));
        assertThat(event("566003@2026-11-04T09:30:00.000+13:00").getMessageHash()).isNotEqualTo(hash);
    }

    @Test
    void changedStatusOnlyIsANewVersionWithTheSameHash() {
        polling.pollNow(NZTA);
        String hash = event("566003@2026-11-03T21:00:06.200+13:00").getMessageHash();

        PollResult changed = polling.pollNow(NZTA, FIXTURES + "events-changed-status.json");

        assertThat(changed.diff()).isEqualTo(new FeedDiff.Result(0, 1, 196, 0, 0, false));
        Event version = event("566003@2026-11-04T09:30:00.000+13:00");
        assertThat(version.getMessageHash()).isEqualTo(hash);
        assertThat(version.getPayload()).contains("\"Resolved\"");
    }

    @Test
    void missingEventIsClearedAfterTwoPolls() {
        polling.pollNow(NZTA);

        PollResult once = polling.pollNow(NZTA, FIXTURES + "events-cleared.json");
        PollResult twice = polling.pollNow(NZTA, FIXTURES + "events-cleared.json");
        PollResult thrice = polling.pollNow(NZTA, FIXTURES + "events-cleared.json");

        assertThat(once.diff()).isEqualTo(new FeedDiff.Result(0, 0, 196, 1, 0, false));
        assertThat(twice.diff()).isEqualTo(new FeedDiff.Result(0, 0, 196, 1, 1, false));
        assertThat(thrice.diff()).isEqualTo(new FeedDiff.Result(0, 0, 196, 0, 0, false));
        List<Event> cleared = nztaEvents().stream()
                .filter(event -> event.getExternalId().startsWith("566003@cleared@")).toList();
        assertThat(cleared).singleElement().satisfies(event -> {
            assertThat(event.getSubjectId()).isEqualTo("566003");
            assertThat(event.getPayload()).contains("TERRACE TUNNEL START");
        });

        PollResult back = polling.pollNow(NZTA);
        assertThat(back.diff()).isEqualTo(new FeedDiff.Result(0, 1, 196, 0, 0, false));
        assertThat(nztaEvents()).filteredOn(event -> event.getExternalId().startsWith("566003@2026-11-03T21:00:06.200"))
                .hasSize(2);
    }

    @Test
    void emptyResponseDoesNotClearManyKnownEvents() {
        polling.pollNow(NZTA);

        for (int i = 0; i < 3; i++) {
            PollResult empty = polling.pollNow(NZTA, FIXTURES + "events-empty.json");
            assertThat(empty.diff()).isEqualTo(new FeedDiff.Result(0, 0, 0, 197, 0, true));
        }
        assertThat(nztaEvents()).hasSize(197);
    }

    @Test
    void failedFetchChangesNothing() {
        polling.pollNow(NZTA);
        OffsetDateTime lastSuccess = nzta().getLastSuccessAt();

        PollResult failed = polling.pollNow(NZTA, FIXTURES + "missing.json");
        polling.pollNow(NZTA, FIXTURES + "missing.json");

        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.diff()).isNull();
        assertThat(nzta().getLastSuccessAt()).isEqualTo(lastSuccess);
        assertThat(nztaEvents()).hasSize(197);
        assertThat(polling.pollNow(NZTA, FIXTURES + "events-cleared.json").diff().missing()).isEqualTo(1);
    }

    @Test
    void disabledSourceIsSkipped() {
        jdbc.update("UPDATE event_source SET enabled = FALSE WHERE name = ?", NZTA);
        try {
            assertThat(polling.pollNow(NZTA).status()).isEqualTo(Status.SKIPPED);
            assertThat(nztaEvents()).isEmpty();
        } finally {
            // the database is shared with other test classes
            jdbc.update("UPDATE event_source SET enabled = TRUE WHERE name = ?", NZTA);
        }
    }

    @Test
    void fewKnownEventsCanAllBeCleared() {
        String source = fakeSource();
        feedDiff.apply(source, POLICY, complete(item("a", "1"), item("b", "1"), item("c", "1")), NOW);

        FeedDiff.Result once = feedDiff.apply(source, POLICY, complete(), NOW.plusMinutes(5));
        FeedDiff.Result twice = feedDiff.apply(source, POLICY, complete(), NOW.plusMinutes(10));

        assertThat(once).isEqualTo(new FeedDiff.Result(0, 0, 0, 3, 0, false));
        assertThat(twice).isEqualTo(new FeedDiff.Result(0, 0, 0, 3, 3, false));
    }

    @Test
    void moreThanHalfMissingClearsNothing() {
        String source = fakeSource();
        List<SourceItem> items = IntStream.range(0, 10).mapToObj(i -> item("e" + i, "1")).toList();
        feedDiff.apply(source, POLICY, SourceSnapshot.complete(items, null, null), NOW);

        SourceSnapshot fourLeft = SourceSnapshot.complete(items.subList(0, 4), null, null);
        SourceSnapshot fiveLeft = SourceSnapshot.complete(items.subList(0, 5), null, null);
        assertThat(feedDiff.apply(source, POLICY, fourLeft, NOW.plusMinutes(5)))
                .isEqualTo(new FeedDiff.Result(0, 0, 4, 6, 0, true));
        assertThat(feedDiff.apply(source, POLICY, fiveLeft, NOW.plusMinutes(10)))
                .isEqualTo(new FeedDiff.Result(0, 0, 5, 5, 0, false));
        assertThat(feedDiff.apply(source, POLICY, fiveLeft, NOW.plusMinutes(15)))
                .isEqualTo(new FeedDiff.Result(0, 0, 5, 5, 5, false));
    }

    @Test
    void partialSnapshotNeverClears() {
        String source = fakeSource();
        feedDiff.apply(source, POLICY, complete(item("a", "1"), item("b", "1")), NOW);

        SourceSnapshot partial = new SourceSnapshot(List.of(item("a", "2")), false, null, null, false);
        for (int i = 1; i <= 3; i++) {
            assertThat(feedDiff.apply(source, POLICY, partial, NOW.plusMinutes(5L * i)))
                    .isEqualTo(new FeedDiff.Result(0, i == 1 ? 1 : 0, i == 1 ? 0 : 1, 0, 0, false));
        }
    }

    @Test
    void notModifiedOnlyUpdatesTheLastSuccess() {
        String source = fakeSource();
        feedDiff.apply(source, POLICY, new SourceSnapshot(List.of(item("a", "1")), true, "\"v1\"", null, false), NOW);

        FeedDiff.Result result = feedDiff.apply(source, POLICY, SourceSnapshot.unchanged(), NOW.plusMinutes(5));

        assertThat(result).isEqualTo(new FeedDiff.Result(0, 0, 0, 0, 0, false));
        EventSource eventSource = eventSourceRepository.findByName(source).orElseThrow();
        assertThat(eventSource.getEtag()).isEqualTo("\"v1\"");
        assertThat(eventSource.getLastSuccessAt()).isAtSameInstantAs(NOW.plusMinutes(5));
    }

    private String fakeSource() {
        String name = "test-" + UUID.randomUUID();
        eventSourceRepository.save(new EventSource(name, EventSourceType.TRAFFIC_FEED));
        return name;
    }

    private static SourceItem item(String subjectId, String version) {
        return new SourceItem(subjectId, version, "{\"id\":\"" + subjectId + "\"}", "Text " + subjectId);
    }

    private static SourceSnapshot complete(SourceItem... items) {
        return SourceSnapshot.complete(List.of(items), null, null);
    }

    private EventSource nzta() {
        return eventSourceRepository.findByName(NZTA).orElseThrow();
    }

    private List<Event> nztaEvents() {
        long sourceId = nzta().getId();
        return eventRepository.findAll().stream().filter(event -> event.getSource().getId() == sourceId).toList();
    }

    private Event event(String externalId) {
        return eventRepository.findBySourceIdAndExternalId(nzta().getId(), externalId).orElseThrow();
    }
}
