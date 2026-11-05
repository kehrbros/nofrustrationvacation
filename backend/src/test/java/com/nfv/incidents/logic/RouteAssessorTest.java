package com.nfv.incidents.logic;

import com.nfv.incidents.config.AssessmentProperties;
import com.nfv.incidents.entities.Category;
import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.entities.MatchKind;
import com.nfv.incidents.entities.VehicleScope;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assessment as a pure function: the example table of Docs/consequence-assessment.md (Terrace Tunnel, closed
 * northbound Sunday to Thursday 21:00–04:30) and the special cases listed there.
 */
class RouteAssessorTest {
    private static final AssessmentProperties RULES = AssessmentProperties.DEFAULTS;
    private static final long BEFORE = 1;
    private static final long TUNNEL = 2;
    private static final long AFTER = 3;
    private static final OffsetDateTime TUNNEL_FROM = at("2026-11-01T21:00");
    private static final OffsetDateTime TUNNEL_TO = at("2026-12-18T04:30");

    // --- example table ---

    @Test
    void closedAtNightTooLateForTheEveningDeadline() {
        RouteAssessor.Assessment tunnel = only(assess(route("20:30", "22:00", "23:30"), List.of(tunnel()),
                at("2026-11-04T23:45")));

        assertThat(tunnel.match()).isEqualTo(MatchKind.DIRECT);
        assertThat(tunnel.delay()).isEqualTo(Duration.ofMinutes(6 * 60 + 30));
        assertThat(tunnel.expectedArrival()).isEqualTo(at("2026-11-05T06:00"));
        assertThat(tunnel.category()).isEqualTo(Category.TOO_LATE);
        assertThat(tunnel.window().startsAt()).isEqualTo(at("2026-11-04T21:00"));
        assertThat(tunnel.window().endsAt()).isEqualTo(at("2026-11-05T04:30"));
    }

    @Test
    void closedAtNightOnlyDelayedForTheNextMorning() {
        RouteAssessor.Assessment tunnel = only(assess(route("20:30", "22:00", "23:30"), List.of(tunnel()),
                at("2026-11-05T10:00")));

        assertThat(tunnel.delay()).isEqualTo(Duration.ofMinutes(6 * 60 + 30));
        assertThat(tunnel.expectedArrival()).isEqualTo(at("2026-11-05T06:00"));
        assertThat(tunnel.category()).isEqualTo(Category.DELAYED);
    }

    @Test
    void noHitAtNoon() {
        assertThat(assess(route("10:30", "12:00", "13:30"), List.of(tunnel()), null)).isEmpty();
    }

    @Test
    void justBeforeTheClosureIsMarginalAndNeverTooLate() {
        // passage 20:55, two hours after departure: ±15 min reaches the closure at 21:00
        RouteAssessor.Assessment tunnel = only(assess(route("18:55", "20:55", "21:30"), List.of(tunnel()),
                at("2026-11-04T23:00")));

        assertThat(tunnel.match()).isEqualTo(MatchKind.MARGINAL);
        assertThat(tunnel.category()).isEqualTo(Category.DELAYED);
        // from the start of the window (just too late) to its end
        assertThat(tunnel.delay()).isEqualTo(Duration.ofMinutes(7 * 60 + 30));
        assertThat(tunnel.window().startsAt()).isEqualTo(at("2026-11-04T21:00"));
    }

    @Test
    void congestionMovesThePassageIntoTheClosure() {
        RouteAssessor.IncidentFacts jam = delay(10, BEFORE, Duration.ofMinutes(30), null);

        List<RouteAssessor.Assessment> result = assess(route("20:00", "20:40", "21:00"), List.of(jam, tunnel()),
                at("2026-11-04T22:00"));

        assertThat(result).hasSize(2);
        RouteAssessor.Assessment first = result.getFirst();
        assertThat(first.incidentId()).isEqualTo(10);
        assertThat(first.category()).isEqualTo(Category.DELAYED);
        assertThat(first.expectedArrival()).isEqualTo(at("2026-11-04T21:30"));
        RouteAssessor.Assessment tunnel = result.getLast();
        assertThat(tunnel.match()).isEqualTo(MatchKind.BY_SHIFT);
        assertThat(tunnel.passageAt()).isEqualTo(at("2026-11-04T21:10"));
        assertThat(tunnel.delay()).isEqualTo(Duration.ofMinutes(7 * 60 + 20));
        assertThat(tunnel.category()).isEqualTo(Category.TOO_LATE);
    }

    // --- special cases ---

    @Test
    void toleranceGrowsWithTheTimeSinceDeparture() {
        assertThat(RULES.tolerance(Duration.ofHours(2))).isEqualTo(Duration.ofMinutes(15));
        assertThat(RULES.tolerance(Duration.ofHours(4))).isEqualTo(Duration.ofMinutes(24));
        assertThat(RULES.tolerance(Duration.ofHours(48))).isEqualTo(Duration.ofHours(2));
        // 3 h 45 min after departure the passage at 21:20 still reaches back to a window ending 21:00
        RouteAssessor.IncidentFacts closure = closure(20, TUNNEL, at("2026-11-04T19:00"), at("2026-11-04T21:00"));
        assertThat(assess(route("17:35", "21:20", "22:00"), List.of(closure), null)).singleElement()
                .satisfies(a -> assertThat(a.match()).isEqualTo(MatchKind.MARGINAL));
        assertThat(assess(route("20:35", "21:20", "22:00"), List.of(closure), null)).isEmpty();
    }

    @Test
    void marginalHitDoesNotAddToTheShift() {
        RouteAssessor.IncidentFacts jam = delay(11, AFTER, Duration.ofMinutes(10), null);

        List<RouteAssessor.Assessment> result = assess(route("18:55", "20:55", "21:30"), List.of(tunnel(), jam),
                at("2026-11-04T21:35"));

        assertThat(result.getFirst().match()).isEqualTo(MatchKind.MARGINAL);
        assertThat(result.getLast().expectedArrival()).isEqualTo(at("2026-11-04T21:40"));
        assertThat(result.getLast().category()).isEqualTo(Category.TOO_LATE);
    }

    @Test
    void cautionWithoutFigureHasNoImpact() {
        RouteAssessor.IncidentFacts caution = delay(12, TUNNEL, null, Duration.ZERO);

        RouteAssessor.Assessment result = only(assess(route("20:30", "22:00", "23:30"), List.of(caution),
                at("2026-11-04T23:35")));

        assertThat(result.category()).isEqualTo(Category.NO_IMPACT);
        assertThat(result.delayAssumed()).isTrue();
        assertThat(result.delay()).isZero();
    }

    @Test
    void assumedDelayCountsAndIsMarked() {
        RouteAssessor.IncidentFacts delays = delay(13, TUNNEL, null, Duration.ofMinutes(15));

        RouteAssessor.Assessment result = only(assess(route("20:30", "22:00", "23:30"), List.of(delays),
                at("2026-11-04T23:40")));

        assertThat(result.delay()).isEqualTo(Duration.ofMinutes(15));
        assertThat(result.delayAssumed()).isTrue();
        assertThat(result.expectedArrival()).isEqualTo(at("2026-11-04T23:45"));
        assertThat(result.category()).isEqualTo(Category.TOO_LATE);
    }

    @Test
    void belowFiveMinutesNoImpact() {
        RouteAssessor.IncidentFacts small = delay(14, TUNNEL, Duration.ofMinutes(4), null);

        RouteAssessor.Assessment result = only(assess(route("20:30", "22:00", "23:30"), List.of(small),
                at("2026-11-04T23:31")));

        // even though the 4 minutes pass the deadline: no own delay worth mentioning
        assertThat(result.category()).isEqualTo(Category.NO_IMPACT);
        assertThat(result.delayAssumed()).isFalse();
    }

    @Test
    void withoutDeadlineNeverTooLate() {
        List<RouteAssessor.Assessment> result = assess(route("20:30", "22:00", "23:30"),
                List.of(tunnel(), closure(21, AFTER, at("2026-11-04T22:00"), null)), null);

        assertThat(result).extracting(RouteAssessor.Assessment::category)
                .containsExactly(Category.DELAYED, Category.DELAYED);
        assertThat(result.getLast().delay()).as("not passable, delay unknown").isNull();
    }

    @Test
    void plannedArrivalAlreadyTooLate() {
        RouteAssessor.IncidentFacts jam = delay(15, BEFORE, Duration.ofMinutes(30), null);
        RouteAssessor.IncidentFacts endless = closure(16, AFTER, at("2026-11-04T20:00"), null);

        List<RouteAssessor.Assessment> result = assess(route("20:30", "22:00", "23:30"), List.of(jam, endless),
                at("2026-11-04T23:00"));

        // delays are rated by themselves, only a closure that cannot be passed is TOO_LATE
        assertThat(result).extracting(RouteAssessor.Assessment::category)
                .containsExactly(Category.DELAYED, Category.TOO_LATE);
    }

    @Test
    void closureWithoutEndCannotBePassed() {
        RouteAssessor.IncidentFacts endless = closure(17, TUNNEL, at("2026-11-04T20:00"), null);
        RouteAssessor.IncidentFacts jam = delay(18, AFTER, Duration.ofMinutes(20), null);
        RouteAssessor.IncidentFacts caution = delay(19, AFTER, null, Duration.ZERO);

        List<RouteAssessor.Assessment> result = assess(route("20:30", "22:00", "23:30"),
                List.of(endless, jam, caution), at("2026-11-05T10:00"));

        assertThat(result.getFirst().delay()).isNull();
        assertThat(result.getFirst().expectedArrival()).isNull();
        assertThat(result).extracting(RouteAssessor.Assessment::category)
                .containsExactly(Category.TOO_LATE, Category.TOO_LATE, Category.NO_IMPACT);
    }

    @Test
    void waitLongerThanMaxWaitCannotBePassed() {
        RouteAssessor.IncidentFacts long_ = closure(22, TUNNEL, at("2026-11-04T20:00"), at("2026-11-05T10:01"));

        RouteAssessor.Assessment result = only(assess(route("20:30", "22:00", "23:30"), List.of(long_),
                at("2026-11-06T10:00")));

        assertThat(result.delay()).isNull();
        assertThat(result.category()).isEqualTo(Category.TOO_LATE);
    }

    @Test
    void heavyVehiclesOnlyHasNoImpactOnCars() {
        RouteAssessor.IncidentFacts heavy = new RouteAssessor.IncidentFacts(23, Impact.BLOCKED, null, null,
                TUNNEL_FROM, TUNNEL_TO, nights(), Set.of(TUNNEL), VehicleScope.HEAVY_ONLY, false);

        RouteAssessor.Assessment result = only(assess(route("20:30", "22:00", "23:30"), List.of(heavy),
                at("2026-11-04T23:45")));

        assertThat(result.delay()).isZero();
        assertThat(result.category()).isEqualTo(Category.NO_IMPACT);
    }

    @Test
    void twoDelaysAddUpPastTheDeadline() {
        RouteAssessor.IncidentFacts first = delay(24, BEFORE, Duration.ofMinutes(20), null);
        RouteAssessor.IncidentFacts second = delay(25, AFTER, Duration.ofMinutes(20), null);

        List<RouteAssessor.Assessment> result = assess(route("20:00", "20:40", "21:00"), List.of(first, second),
                at("2026-11-04T21:30"));

        assertThat(result).extracting(RouteAssessor.Assessment::expectedArrival)
                .containsExactly(at("2026-11-04T21:20"), at("2026-11-04T21:40"));
        assertThat(result).extracting(RouteAssessor.Assessment::category)
                .containsExactly(Category.DELAYED, Category.TOO_LATE);
    }

    @Test
    void closureWithUnconfirmedTimesIsDelayedWithoutShiftAndNeverTooLate() {
        RouteAssessor.IncidentFacts jam = delay(30, BEFORE, Duration.ofMinutes(20), null);
        RouteAssessor.IncidentFacts unconfirmed = new RouteAssessor.IncidentFacts(31, Impact.DELAYED, null, null,
                TUNNEL_FROM, TUNNEL_TO, List.of(), Set.of(TUNNEL), VehicleScope.ALL, true);

        // the jam alone passes the deadline: the closure after it still stays DELAYED and adds nothing
        List<RouteAssessor.Assessment> result = assess(route("20:30", "22:00", "23:30"), List.of(jam, unconfirmed),
                at("2026-11-04T23:40"));

        assertThat(result).extracting(RouteAssessor.Assessment::category)
                .containsExactly(Category.TOO_LATE, Category.DELAYED);
        RouteAssessor.Assessment closure = result.getLast();
        assertThat(closure.delay()).isZero();
        assertThat(closure.delayAssumed()).isFalse();
        assertThat(closure.expectedArrival()).isEqualTo(at("2026-11-04T23:50"));
    }

    // --- helpers ---

    /**
     * Three sections on Wednesday 4.11.: before the tunnel from {@code departure}, through the tunnel (1 min) from
     * {@code tunnel}, after it until {@code arrival}.
     */
    private static List<RouteAssessor.Section> route(String departure, String tunnel, String arrival) {
        OffsetDateTime start = at("2026-11-04T" + departure);
        OffsetDateTime inTunnel = at("2026-11-04T" + tunnel);
        OffsetDateTime end = at("2026-11-04T" + arrival);
        return List.of(new RouteAssessor.Section(101, BEFORE, start, inTunnel),
                new RouteAssessor.Section(102, TUNNEL, inTunnel, inTunnel.plusMinutes(1)),
                new RouteAssessor.Section(103, AFTER, inTunnel.plusMinutes(1), end));
    }

    private static List<RouteAssessor.Assessment> assess(List<RouteAssessor.Section> sections,
                                                         List<RouteAssessor.IncidentFacts> incidents,
                                                         @Nullable OffsetDateTime deadline) {
        return RouteAssessor.assess(sections, incidents, sections.getLast().passageEnd(), deadline, RULES);
    }

    private static RouteAssessor.Assessment only(List<RouteAssessor.Assessment> assessments) {
        assertThat(assessments).hasSize(1);
        return assessments.getFirst();
    }

    /** 566003: closed Sunday to Thursday nights 21:00–04:30. */
    private static RouteAssessor.IncidentFacts tunnel() {
        return new RouteAssessor.IncidentFacts(566003, Impact.BLOCKED, null, null, TUNNEL_FROM, TUNNEL_TO, nights(),
                Set.of(TUNNEL), VehicleScope.ALL, false);
    }

    private static List<TimeWindow> nights() {
        List<TimeWindow> nights = new ArrayList<>();
        for (LocalDate night = LocalDate.of(2026, 11, 1); night.isBefore(LocalDate.of(2026, 12, 18));
             night = night.plusDays(1)) {
            if (night.getDayOfWeek().getValue() <= 4 || night.getDayOfWeek().getValue() == 7) {
                nights.add(new TimeWindow(at(night + "T21:00"), at(night.plusDays(1) + "T04:30")));
            }
        }
        return nights;
    }

    private static RouteAssessor.IncidentFacts closure(long id, long segment, OffsetDateTime from,
                                                       @Nullable OffsetDateTime to) {
        return new RouteAssessor.IncidentFacts(id, Impact.BLOCKED, null, null, from, to, List.of(), Set.of(segment),
                VehicleScope.ALL, false);
    }

    private static RouteAssessor.IncidentFacts delay(long id, long segment, @Nullable Duration expected,
                                                     @Nullable Duration assumed) {
        return new RouteAssessor.IncidentFacts(id, Impact.DELAYED, expected, assumed, TUNNEL_FROM, null, List.of(),
                Set.of(segment), VehicleScope.ALL, false);
    }

    private static OffsetDateTime at(String local) {
        return OffsetDateTime.parse(local + ":00+13:00");
    }
}
