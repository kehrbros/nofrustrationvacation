package com.nfv.advisor.logic;

import com.nfv.advisor.config.AdvisorProperties;
import com.nfv.advisor.entities.ProposalKind;
import com.nfv.assistant.logic.ToolInputException;
import com.nfv.network.logic.PlaceKind;
import com.nfv.network.logic.RoutedSegment;
import com.nfv.recommendations.logic.InvalidSuggestionInputException;
import com.nfv.recommendations.logic.Stopovers;
import com.nfv.recommendations.logic.Stopovers.OvernightArea;
import com.nfv.recommendations.logic.Stopovers.OvernightAreas;
import com.nfv.recommendations.logic.Stopovers.StopoverProblem;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** See Docs/advisor.md#find_overnight_areas. */
class OvernightAreaToolTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.of(2027, 7, 12, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final OvernightArea AREA = new OvernightArea("Taupō", PlaceKind.TOWN, -38.68, 176.07,
            DEPARTURE.plusHours(3), 143_240, 1_120);
    private static final List<RoutedSegment> PATH = List.of(new RoutedSegment(1, 0, 1, 1000, Duration.ofMinutes(1)));

    private final Stopovers stopovers = mock(Stopovers.class);
    private final OvernightAreaTool tool = new OvernightAreaTool(stopovers, properties(true));
    private final RunContext run = run(true);

    @Test
    void nullAndKeepRouteLookAlongThePlannedRouteAndAreasGetIds() {
        when(stopovers.onPlannedRoute(1, 2, null, null)).thenReturn(new OvernightAreas(List.of(AREA), null));

        JsonNode answer = tool.call(run, arguments(null, null, null));
        tool.call(run, arguments("keep_route", null, null));

        assertThat(answer.path("problem").isNull()).isTrue();
        JsonNode area = answer.path("areas").get(0);
        assertThat(area.path("area_id").asString()).isEqualTo("area_1");
        assertThat(area.path("name").asString()).isEqualTo("Taupō");
        assertThat(area.path("kind").asString()).isEqualTo("TOWN");
        assertThat(area.path("arrival_at").asString()).isEqualTo(DEPARTURE.plusHours(3).toString());
        assertThat(area.path("remaining_km").asDouble()).isEqualTo(143.2);
        assertThat(area.path("off_route_km").asDouble()).isEqualTo(1.1);
        assertThat(run.area("area_1")).hasValueSatisfying(known -> {
            assertThat(known.name()).isEqualTo("Taupō");
            assertThat(known.routeOptionId()).isNull();
        });
    }

    @Test
    void routeOptionLooksAlongItsPathAndItsAreasRememberIt() {
        String id = run.register(draft(ProposalKind.REROUTE));
        OffsetDateTime limit = DEPARTURE.plusHours(5);
        when(stopovers.onOption(1, 2, PATH, DEPARTURE, limit, 3)).thenReturn(new OvernightAreas(List.of(AREA), null));

        JsonNode answer = tool.call(run, arguments(id, limit.toString(), 3));

        assertThat(answer.path("route_option_id").asString()).isEqualTo(id);
        assertThat(run.area("area_1")).hasValueSatisfying(known -> assertThat(known.routeOptionId()).isEqualTo(id));
    }

    @Test
    void areaCannotBeProposed() {
        when(stopovers.onPlannedRoute(1, 2, null, null)).thenReturn(new OvernightAreas(List.of(AREA), null));

        tool.call(run, arguments(null, null, null));

        assertThat(run.area("area_1")).isPresent();
        assertThat(run.option("area_1")).isEmpty();
    }

    @Test
    void problemGoesToTheModel() {
        when(stopovers.onPlannedRoute(1, 2, null, null))
                .thenReturn(new OvernightAreas(List.of(), StopoverProblem.NOTHING_REACHABLE));

        JsonNode answer = tool.call(run, arguments(null, null, null));

        assertThat(answer.path("problem").asString()).isEqualTo("NOTHING_REACHABLE");
        assertThat(answer.path("areas")).isEmpty();
    }

    @Test
    void wrongInputGoesBackToTheModel() {
        String shift = run.register(draft(ProposalKind.SHIFT_DEPARTURE));
        when(stopovers.onPlannedRoute(anyLong(), anyLong(), any(), eq(9)))
                .thenThrow(new InvalidSuggestionInputException("max_results must be between 1 and 5"));

        assertThatThrownBy(() -> tool.call(run, arguments("route_99", null, null)))
                .isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> tool.call(run, arguments(shift, null, null)))
                .isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> tool.call(run, arguments(null, "tonight", null)))
                .isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> tool.call(run, arguments(null, null, 9)))
                .isInstanceOf(ToolInputException.class);
    }

    @Test
    void appliesOnlyIfEnabledAndRelevant() {
        assertThat(tool.appliesTo(run(true))).isTrue();
        assertThat(tool.appliesTo(run(false))).isFalse();
        assertThat(new OvernightAreaTool(stopovers, properties(false)).appliesTo(run(true))).isFalse();
    }

    @Test
    void parametersAreStrict() {
        JsonNode parameters = tool.parameters();

        assertThat(parameters.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(parameters.path("required")).hasSize(3);
    }

    private static JsonNode arguments(String option, String limit, Integer count) {
        return JSON.createObjectNode().put("route_option_id", option).put("latest_arrival", limit)
                .put("max_results", count);
    }

    private static AdvisorProperties properties(boolean enabled) {
        return new AdvisorProperties(8, 3, Duration.ofMinutes(15), false, enabled);
    }

    private static RunContext run(boolean relevant) {
        return new RunContext(1, 2, false, null, relevant, draft(ProposalKind.KEEP_ROUTE));
    }

    private static ProposalDraft draft(ProposalKind kind) {
        return new ProposalDraft(kind, null, DEPARTURE, DEPARTURE.plusHours(6), 1000.0, Duration.ofHours(6),
                Duration.ZERO, null, null, List.of(), List.of(), kind == ProposalKind.KEEP_ROUTE ? List.of() : PATH);
    }
}
