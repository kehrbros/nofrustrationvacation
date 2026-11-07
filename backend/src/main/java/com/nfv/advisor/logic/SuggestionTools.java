package com.nfv.advisor.logic;

import com.nfv.assistant.logic.ToolInputException;
import com.nfv.recommendations.logic.InvalidSuggestionInputException;
import com.nfv.recommendations.logic.OptionKind;
import com.nfv.recommendations.logic.SuggestionResult;
import com.nfv.recommendations.logic.Suggestions;
import jakarta.annotation.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;
import java.util.function.Predicate;

/** The tools on {@code recommendations}, see Docs/advisor.md#tools. */
@Configuration
class SuggestionTools {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    AdvisorTool findAlternativeRoutes(Suggestions suggestions) {
        return new SuggestionTool(suggestions, "find_alternative_routes",
                "Ways around the incidents on the route that keep the waypoints of the user, at the planned departure.",
                """
                {"type":"object","properties":{"count":{"type":["integer","null"],
                "description":"Options wanted, 1 to 5, null for the default"}},
                "required":["count"],"additionalProperties":false}""",
                OptionKind.ALTERNATIVES, _ -> true);
    }

    @Bean
    AdvisorTool findDepartureShifts(Suggestions suggestions) {
        return new SuggestionTool(suggestions, "find_departure_shifts",
                "The planned route with an earlier or later departure that passes outside of the incidents, at most one "
                        + "in each direction. Empty if no departure helps.",
                """
                {"type":"object","properties":{},"required":[],"additionalProperties":false}""",
                OptionKind.DEPARTURE_SHIFT, run -> !run.underway());
    }

    private record SuggestionTool(Suggestions suggestions, String name, String description, String schema,
                                  OptionKind kind, Predicate<RunContext> applies) implements AdvisorTool {
        @Override
        public JsonNode parameters() {
            return JSON.readTree(schema);
        }

        @Override
        public boolean appliesTo(RunContext run) {
            return applies.test(run);
        }

        @Override
        public JsonNode call(RunContext run, JsonNode arguments) {
            SuggestionResult result;
            try {
                result = suggestions.forConsequence(run.appUserId(), run.consequenceId(), Set.of(kind), count(arguments),
                        null);
            } catch (InvalidSuggestionInputException e) {
                throw new ToolInputException(e.getMessage());
            }
            ObjectNode answer = JSON.createObjectNode();
            answer.put("problem", result.problem() == null ? null : result.problem().name());
            ArrayNode options = answer.putArray("options");
            result.options().forEach(option -> {
                ProposalDraft draft = ProposalDraft.of(option, run.deadline());
                options.add(draft.describe(JSON, run.register(draft)));
            });
            return answer;
        }

        private static @Nullable Integer count(JsonNode arguments) {
            JsonNode count = arguments.path("count");
            return count.isIntegralNumber() ? count.asInt() : null;
        }
    }
}
