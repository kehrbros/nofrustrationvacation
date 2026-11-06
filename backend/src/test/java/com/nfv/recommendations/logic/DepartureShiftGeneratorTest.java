package com.nfv.recommendations.logic;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DepartureShiftGeneratorTest {
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.of(2027, 7, 12, 20, 0, 0, 0, ZoneOffset.UTC);
    private static final Duration BUFFER = Duration.ofMinutes(15);
    private static final Duration MAX = Duration.ofHours(24);
    private static final OffsetDateTime EARLIEST = DEPARTURE.minusHours(12);

    /** Place reached 2 h after the departure, closed 21:00–04:30. */
    private static final Scenario.Avoid NIGHT = new Scenario.Avoid(Duration.ofHours(2),
            Duration.ofHours(2).plusMinutes(5), DEPARTURE.plusHours(1), DEPARTURE.plusHours(8).plusMinutes(30));

    @Test
    void laterReachesThePlaceAfterTheEndPlusBuffer() {
        assertThat(DepartureShiftGenerator.later(List.of(NIGHT), DEPARTURE, BUFFER, MAX))
                .contains(DEPARTURE.plusHours(6).plusMinutes(45));
    }

    @Test
    void earlierLeavesThePlaceBeforeTheStartMinusBuffer() {
        assertThat(DepartureShiftGenerator.earlier(List.of(NIGHT), DEPARTURE, EARLIEST, BUFFER))
                .contains(DEPARTURE.minusHours(1).minusMinutes(20));
    }

    @Test
    void laterHasToPassTheLastOfSeveral() {
        Scenario.Avoid afterwards = new Scenario.Avoid(Duration.ofHours(3), Duration.ofHours(3),
                DEPARTURE, DEPARTURE.plusHours(12));

        assertThat(DepartureShiftGenerator.later(List.of(NIGHT, afterwards), DEPARTURE, BUFFER, MAX))
                .contains(DEPARTURE.plusHours(9).plusMinutes(15));
    }

    @Test
    void noLaterWithoutAnEnd() {
        Scenario.Avoid open = new Scenario.Avoid(Duration.ofHours(1), Duration.ofHours(1), DEPARTURE, null);

        assertThat(DepartureShiftGenerator.later(List.of(NIGHT, open), DEPARTURE, BUFFER, MAX)).isEmpty();
    }

    @Test
    void noLaterBeyondTheLimit() {
        assertThat(DepartureShiftGenerator.later(List.of(NIGHT), DEPARTURE, BUFFER, Duration.ofHours(6))).isEmpty();
    }

    @Test
    void noEarlierBeforeTheEarliestDeparture() {
        assertThat(DepartureShiftGenerator.earlier(List.of(NIGHT), DEPARTURE, DEPARTURE.minusHours(1), BUFFER)).isEmpty();
    }
}
