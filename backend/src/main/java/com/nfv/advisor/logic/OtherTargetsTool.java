package com.nfv.advisor.logic;

import com.nfv.advisor.config.AdvisorProperties;
import com.nfv.assistant.logic.ToolInputException;
import com.nfv.network.logic.GeoPoint;
import com.nfv.recommendations.logic.Destinations;
import com.nfv.recommendations.logic.InvalidSuggestionInputException;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * {@code find_other_targets}: places around the target that can be reached when the target cannot, see
 * Docs/incident-plans.md#rules. Each becomes a {@code place_} for {@code search_accommodations} and
 * {@code compose_plan}.
 */
@Component
@RequiredArgsConstructor
class OtherTargetsTool implements AdvisorTool {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final int DEFAULT_RESULTS = 3;
    private static final String SCHEMA = """
            {"type":"object","properties":{
            "latest_arrival":{"type":["string","null"],
            "description":"Latest arrival as ISO date and time with offset, null for no limit"},
            "max_results":{"type":["integer","null"],"description":"Places wanted, 1 to 5, null for 3"}},
            "required":["latest_arrival","max_results"],"additionalProperties":false}""";

    private final Destinations destinations;
    private final AdvisorProperties properties;

    @Override
    public String name() {
        return "find_other_targets";
    }

    @Override
    public String description() {
        return "Only if the target stop cannot be reached at all: towns and villages around it that can be reached, "
                + "closest to the target and to the following stops first, with the drive there. Search a stay at a "
                + "place_ with search_accommodations and build the plan with compose_plan.";
    }

    @Override
    public JsonNode parameters() {
        return JSON.readTree(SCHEMA);
    }

    @Override
    public boolean appliesTo(RunContext run) {
        return properties.plansEnabled() && properties.stopoverEnabled() && run.overnightRelevant()
                && run.target() != null;
    }

    @Override
    public JsonNode call(RunContext run, JsonNode arguments) {
        JsonNode count = arguments.path("max_results");
        int maxResults = count.isIntegralNumber() ? count.asInt() : DEFAULT_RESULTS;
        RunContext.NextStop next = run.nextStop();
        Destinations.Found found;
        try {
            found = destinations.around(run.appUserId(), run.consequenceId(),
                    next == null ? null : new GeoPoint(next.latitude(), next.longitude()), latestArrival(arguments),
                    maxResults);
        } catch (InvalidSuggestionInputException e) {
            throw new ToolInputException(e.getMessage());
        }
        ObjectNode answer = JSON.createObjectNode();
        answer.put("problem", found.problem());
        answer.put("next_stop", next == null ? null : next.name());
        ArrayNode places = answer.putArray("places");
        for (Destinations.Destination destination : found.destinations()) {
            ProposalDraft drive = ProposalDraft.of(destination.drive(), null);
            String id = run.registerPlace(new RunContext.Place(destination.name(), destination.latitude(),
                    destination.longitude(), drive));
            ObjectNode place = places.addObject().put("place_id", id).put("name", destination.name())
                    .put("kind", destination.kind().name())
                    .put("from_target_km", kilometers(destination.distanceToTargetMeters()));
            place.put("from_next_stop_km", destination.distanceToNextMeters() == null ? null
                    : kilometers(destination.distanceToNextMeters()));
            place.put("arrival_at", destination.drive().arrivalAt().toString());
            place.put("driving_minutes", destination.drive().duration().toMinutes());
        }
        return answer;
    }

    private static @Nullable OffsetDateTime latestArrival(JsonNode arguments) {
        JsonNode text = arguments.path("latest_arrival");
        if (!text.isString()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text.asString());
        } catch (DateTimeParseException e) {
            throw new ToolInputException("latest_arrival must be an ISO date and time with offset");
        }
    }

    private static double kilometers(double meters) {
        return Math.round(meters / 100) / 10.0;
    }
}
