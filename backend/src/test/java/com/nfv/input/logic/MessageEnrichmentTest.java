package com.nfv.input.logic;

import com.nfv.incidents.logic.IncidentOverlap;
import com.nfv.incidents.logic.TimeWindow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks of the time windows from the model: zone, length, count, validity, see Docs/traffic-feed.md#anreicherung-messageenrichment. */
class MessageEnrichmentTest {
    private static final ZoneId AUCKLAND = ZoneId.of("Pacific/Auckland");
    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-11-01T21:00:26+13:00");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-12-18T04:30:26+13:00");

    @Test
    void localTimesAreConvertedWithTheZoneOfTheRegion() {
        Optional<List<TimeWindow>> windows = check(window("2026-11-04T21:00", "2026-11-05T04:30"));

        assertThat(windows).hasValueSatisfying(list -> assertThat(list).singleElement().satisfies(window -> {
            assertThat(window.startsAt()).isEqualTo(OffsetDateTime.parse("2026-11-04T21:00+13:00"));
            assertThat(window.endsAt()).isEqualTo(OffsetDateTime.parse("2026-11-05T04:30+13:00"));
        }));
        // New Zealand standard time (+12) in winter
        assertThat(MessageEnrichment.check(List.of(window("2027-06-01T21:00", "2027-06-02T04:30")), AUCKLAND,
                OffsetDateTime.parse("2027-01-01T00:00+13:00"), null).orElseThrow().getFirst().startsAt())
                .isEqualTo(OffsetDateTime.parse("2027-06-01T21:00+12:00"));
    }

    @Test
    void windowsAreCutToTheValidityAndNeverExtendIt() {
        List<TimeWindow> windows = check(
                window("2026-11-01T21:00", "2026-11-02T04:30"),
                window("2026-12-17T21:00", "2026-12-18T05:00"),
                window("2026-12-20T21:00", "2026-12-21T04:30")).orElseThrow();

        assertThat(windows).hasSize(2);
        assertThat(windows.getFirst().startsAt()).isEqualTo(FROM);
        assertThat(windows.getLast().endsAt()).isEqualTo(TO);
        assertThat(check(window("2027-01-01T21:00", "2027-01-02T04:30"))).as("all outside").isEmpty();
    }

    @Test
    void invalidWindowsRejectTheWholeAnswer() {
        assertThat(check(window("2026-11-04T21:00", "2026-11-04T20:00"))).as("ends before it starts").isEmpty();
        assertThat(check(window("2026-11-04T21:00", "2026-11-04T21:00"))).as("empty").isEmpty();
        assertThat(check(window("2026-11-04T21:00", "2026-11-05T21:01"))).as("over 24 h").isEmpty();
        assertThat(check(window("2026-11-04T21:00", "2026-11-05T21:00"))).as("exactly 24 h").isPresent();
        assertThat(check(window("2026-11-04T21:00", "2026-11-05T04:30"),
                window("2026-11-06T21:00", "2026-11-06T20:00"))).as("one bad window").isEmpty();
    }

    @Test
    void atMost200Windows() {
        LocalDateTime start = LocalDateTime.parse("2026-11-02T08:00");
        List<EnrichmentModel.LocalWindow> many = IntStream.range(0, 201)
                .mapToObj(i -> new EnrichmentModel.LocalWindow(start.plusHours(i * 2L), start.plusHours(i * 2L + 1)))
                .toList();

        assertThat(MessageEnrichment.check(many.subList(0, 200), AUCKLAND, FROM, TO)).hasValueSatisfying(
                list -> assertThat(list).hasSize(200));
        assertThat(MessageEnrichment.check(many, AUCKLAND, FROM, TO)).isEmpty();
    }

    @Test
    void noWindowsMeansThroughout() {
        assertThat(MessageEnrichment.check(List.of(), AUCKLAND, FROM, TO)).hasValue(List.of());
    }

    @Test
    void overlapNeedsAWindowWhenThereAreAny() {
        List<TimeWindow> nights = check(window("2026-11-04T21:00", "2026-11-05T04:30"),
                window("2026-11-05T21:00", "2026-11-06T04:30")).orElseThrow();
        OffsetDateTime noon = OffsetDateTime.parse("2026-11-04T12:00+13:00");
        OffsetDateTime late = OffsetDateTime.parse("2026-11-05T22:00+13:00");

        assertThat(IncidentOverlap.overlaps(FROM, TO, nights, noon, noon.plusMinutes(5))).isFalse();
        assertThat(IncidentOverlap.overlaps(FROM, TO, List.of(), noon, noon.plusMinutes(5))).isTrue();
        assertThat(IncidentOverlap.firstWindow(nights, late, late.plusMinutes(5))).hasValue(nights.getLast());
        // a passage reaching into a window counts
        OffsetDateTime before = OffsetDateTime.parse("2026-11-04T20:55+13:00");
        assertThat(IncidentOverlap.overlaps(FROM, TO, nights, before, before.plusMinutes(10))).isTrue();
    }

    @Test
    void validityWithoutLengthOverlapsNothing() {
        assertThat(IncidentOverlap.overlaps(FROM, FROM, List.of(), FROM.minusHours(1), FROM.plusHours(1))).isFalse();
    }

    private static Optional<List<TimeWindow>> check(EnrichmentModel.LocalWindow... windows) {
        return MessageEnrichment.check(List.of(windows), AUCKLAND, FROM, TO);
    }

    private static EnrichmentModel.LocalWindow window(String start, String end) {
        return new EnrichmentModel.LocalWindow(LocalDateTime.parse(start), LocalDateTime.parse(end));
    }
}
