package com.nfv.recommendations.logic;

import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.entities.VehicleScope;
import com.nfv.incidents.logic.ActiveIncident;
import com.nfv.network.logic.RouteOptions;
import jakarta.annotation.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateEvaluatorTest {
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.of(2027, 7, 12, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final List<CandidateEvaluator.Step> STEPS =
            List.of(new CandidateEvaluator.Step(1, 1000, Duration.ofMinutes(10)));

    @Test
    void assumedDelayCountsLikeInTheAssessment() {
        CandidateEvaluator.Outcome outcome = evaluate(incident(Impact.DELAYED, null, Duration.ofMinutes(15),
                VehicleScope.ALL));

        assertThat(outcome.delay()).isEqualTo(Duration.ofMinutes(15));
        assertThat(outcome.conflicts().getFirst().delay()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void givenDelayWinsOverAssumption() {
        CandidateEvaluator.Outcome outcome = evaluate(incident(Impact.DELAYED, Duration.ofMinutes(40),
                Duration.ofMinutes(15), VehicleScope.ALL));

        assertThat(outcome.delay()).isEqualTo(Duration.ofMinutes(40));
    }

    @Test
    void heavyOnlyDoesNotAffectCars() {
        CandidateEvaluator.Outcome outcome = evaluate(incident(Impact.BLOCKED, null, null, VehicleScope.HEAVY_ONLY));

        assertThat(outcome.blocked()).isFalse();
        assertThat(outcome.conflicts()).isEmpty();
    }

    @Test
    void passagesAreMovedByTheDelaysBeforeThemAndMarkTheClosure() {
        List<CandidateEvaluator.Step> steps = List.of(new CandidateEvaluator.Step(1, 1000, Duration.ofMinutes(10)),
                new CandidateEvaluator.Step(2, 2000, Duration.ofMinutes(20)),
                new CandidateEvaluator.Step(3, 3000, Duration.ofMinutes(30)));
        ActiveIncident delay = incident(Impact.DELAYED, Duration.ofMinutes(5), null, VehicleScope.ALL);
        ActiveIncident closure = new ActiveIncident(8, Impact.BLOCKED, null, null, DEPARTURE.minusHours(1),
                DEPARTURE.plusHours(5), Set.of(2L), List.of(), VehicleScope.ALL);

        CandidateEvaluator.Outcome outcome = CandidateEvaluator.evaluate(steps, DEPARTURE, RouteOptions.none(),
                List.of(delay, closure));

        assertThat(outcome.passages()).extracting(CandidateEvaluator.Passage::blocked)
                .containsExactly(false, true, false);
        assertThat(outcome.passages().get(0).end()).isEqualTo(DEPARTURE.plusMinutes(10));
        assertThat(outcome.passages().get(1).start()).isEqualTo(DEPARTURE.plusMinutes(15));
        assertThat(outcome.passages().get(2).start()).isEqualTo(DEPARTURE.plusMinutes(35));
        assertThat(outcome.passages().get(2).metersBefore()).isEqualTo(3000);
        assertThat(outcome.blocked()).isTrue();
    }

    private static CandidateEvaluator.Outcome evaluate(ActiveIncident incident) {
        return CandidateEvaluator.evaluate(STEPS, DEPARTURE, RouteOptions.none(), List.of(incident));
    }

    private static ActiveIncident incident(Impact impact, @Nullable Duration expected, @Nullable Duration assumed,
                                           VehicleScope scope) {
        return new ActiveIncident(7, impact, expected, assumed, DEPARTURE.minusHours(1), DEPARTURE.plusHours(1),
                Set.of(1L), List.of(), scope);
    }
}
