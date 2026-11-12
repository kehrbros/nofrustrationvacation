package com.nfv.demo;

import com.nfv.demo.logic.DemoScenario;
import com.nfv.demo.logic.DemoScenario.DemoIncident;
import com.nfv.demo.logic.DemoScenario.DemoStop;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The demo trip fits together: times in order, stays within their stops, incidents on existing routes. */
class DemoScenarioTest {
    private static final LocalDate FIRST_DAY = LocalDate.of(2026, 11, 13);
    private final DemoScenario scenario = new DemoScenario(FIRST_DAY, DemoScenario.Language.EN,
            OffsetDateTime.parse("2026-11-12T05:00:00Z"));

    @Test
    void stopsFollowEachOtherWithinTheTrip() {
        List<DemoStop> stops = scenario.stops();
        assertThat(stops.getFirst().arrivalAt()).isNull();
        OffsetDateTime previous = scenario.startAt();
        for (DemoStop stop : stops) {
            if (stop.arrivalAt() != null) {
                assertThat(stop.arrivalAt()).as(stop.name()).isAfter(previous);
                previous = stop.arrivalAt();
            }
            assertThat(stop.departureAt()).as(stop.name()).isAfter(previous);
            previous = stop.departureAt();
        }
        assertThat(previous).isBefore(scenario.endAt());
        assertThat(scenario.startAt().toLocalDate()).isEqualTo(FIRST_DAY);
        assertThat(scenario.endAt().toLocalDate()).isEqualTo(FIRST_DAY.plusDays(DemoScenario.DAYS - 1L));
    }

    @Test
    void staysLieWithinTheDaysOfTheirStop() {
        for (DemoStop stop : scenario.stops()) {
            if (stop.stay() == null) {
                continue;
            }
            LocalDate arrival = stop.arrivalAt().atZoneSameInstant(DemoScenario.ZONE).toLocalDate();
            LocalDate departure = stop.departureAt().atZoneSameInstant(DemoScenario.ZONE).toLocalDate();
            assertThat(stop.stay().checkIn().toLocalDate()).as(stop.name()).isEqualTo(arrival);
            assertThat(stop.stay().checkOut().toLocalDate()).as(stop.name()).isEqualTo(departure);
            assertThat(stop.stay().checkInUntil()).isAfter(stop.stay().checkInFrom());
        }
    }

    @Test
    void incidentsLieOnRoutesOfTheTripAndHaveDistinctKeys() {
        List<DemoIncident> incidents = scenario.incidents();
        assertThat(incidents).extracting(DemoIncident::key).doesNotHaveDuplicates();
        assertThat(incidents).allSatisfy(incident -> {
            // the first route was driven on day 1, its passage is over
            assertThat(incident.fromStop()).isBetween(1, scenario.stops().size() - 2);
            if (incident.validTo() != null) {
                assertThat(incident.validTo()).isAfter(incident.validFrom());
            }
            // a time of day in the text would make a closure "times unconfirmed"
            assertThat(incident.text()).doesNotContainPattern("\\d{1,2}[:.]\\d{2}");
        });
    }

    @Test
    void namesFollowTheLanguage() {
        DemoScenario german = new DemoScenario(FIRST_DAY, DemoScenario.Language.DE, OffsetDateTime.now());
        assertThat(german.tripName()).isEqualTo("Südinsel Neuseeland (Demo)");
        assertThat(scenario.tripName()).isEqualTo("New Zealand South Island (demo)");
    }
}
