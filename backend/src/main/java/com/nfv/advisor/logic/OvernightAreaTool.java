package com.nfv.advisor.logic;

import com.nfv.advisor.config.AdvisorProperties;
import com.nfv.advisor.entities.ProposalKind;
import com.nfv.assistant.logic.ToolInputException;
import com.nfv.recommendations.logic.InvalidSuggestionInputException;
import com.nfv.recommendations.logic.Stopovers;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/** {@code find_overnight_areas}: places for a stay along a way, see Docs/advisor.md#find_overnight_areas. */
@Component
@RequiredArgsConstructor
class OvernightAreaTool implements AdvisorTool {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SCHEMA = """
            {"type":"object","properties":{
            "route_option_id":{"type":["string","null"],
            "description":"A route_ option to look along, null or keep_route for the planned route"},
            "latest_arrival":{"type":["string","null"],
            "description":"Latest arrival as ISO date and time with offset, null for the evening limit of the region"},
            "max_results":{"type":["integer","null"],"description":"Places wanted, 1 to 5, null for the default"}},
            "required":["route_option_id","latest_arrival","max_results"],"additionalProperties":false}""";

    private final Stopovers stopovers;
    private final AdvisorProperties properties;

    @Override
    public String name() {
        return "find_overnight_areas";
    }

    @Override
    public String description() {
        return "Towns and villages for a stay along a way, before anything that blocks it and reachable by the latest "
                + "arrival. arrival_at is approximate: it leaves out the way off the route, and off_route_km is a "
                + "straight line. An area is no option and cannot be proposed.";
    }

    @Override
    public JsonNode parameters() {
        return JSON.readTree(SCHEMA);
    }

    @Override
    public boolean appliesTo(RunContext run) {
        return properties.stopoverEnabled() && run.overnightRelevant();
    }

    @Override
    public JsonNode call(RunContext run, JsonNode arguments) {
        String optionId = routeOptionId(arguments);
        OffsetDateTime latestArrival = latestArrival(arguments);
        JsonNode count = arguments.path("max_results");
        Integer maxResults = count.isIntegralNumber() ? count.asInt() : null;
        Stopovers.OvernightAreas result;
        try {
            if (optionId == null) {
                result = stopovers.onPlannedRoute(run.appUserId(), run.consequenceId(), latestArrival, maxResults);
            } else {
                ProposalDraft option = route(run, optionId);
                result = stopovers.onOption(run.appUserId(), run.consequenceId(), option.path(),
                        Objects.requireNonNull(option.departureAt()), latestArrival, maxResults);
            }
        } catch (InvalidSuggestionInputException e) {
            throw new ToolInputException(e.getMessage());
        }
        ObjectNode answer = JSON.createObjectNode();
        answer.put("problem", result.problem() == null ? null : result.problem().name());
        answer.put("route_option_id", optionId);
        ArrayNode areas = answer.putArray("areas");
        result.areas().forEach(area -> areas.addObject()
                .put("area_id", run.registerArea(new RunContext.Area(area.name(), area.latitude(), area.longitude(),
                        optionId)))
                .put("name", area.name())
                .put("kind", area.kind().name())
                .put("arrival_at", area.arrivalAt().toString())
                .put("remaining_km", kilometers(area.remainingMeters()))
                .put("off_route_km", kilometers(area.offRouteMeters())));
        return answer;
    }

    /** @return {@code null} for the planned route */
    private static @Nullable String routeOptionId(JsonNode arguments) {
        JsonNode id = arguments.path("route_option_id");
        return !id.isString() || RunContext.KEEP_ROUTE.equals(id.asString()) ? null : id.asString();
    }

    /** A way is needed: a shifted departure exists to pass outside of the incident, a stay on it makes no sense. */
    private static ProposalDraft route(RunContext run, String id) {
        ProposalDraft option = run.option(id)
                .orElseThrow(() -> new ToolInputException("unknown route_option_id " + id));
        if (option.kind() != ProposalKind.REROUTE) {
            throw new ToolInputException("route_option_id must be keep_route or a route_ option");
        }
        return option;
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
