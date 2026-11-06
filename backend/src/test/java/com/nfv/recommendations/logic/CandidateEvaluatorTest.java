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

    private static CandidateEvaluator.Outcome evaluate(ActiveIncident incident) {
        return CandidateEvaluator.evaluate(STEPS, DEPARTURE, RouteOptions.none(), List.of(incident));
    }

    private static ActiveIncident incident(Impact impact, @Nullable Duration expected, @Nullable Duration assumed,
                                           VehicleScope scope) {
        return new ActiveIncident(7, impact, expected, assumed, DEPARTURE.minusHours(1), DEPARTURE.plusHours(1),
                Set.of(1L), List.of(), scope);
    }
}
