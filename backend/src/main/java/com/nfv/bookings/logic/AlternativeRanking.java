package com.nfv.bookings.logic;

import com.nfv.assistant.logic.AssistantRateLimitedException;
import com.nfv.assistant.logic.AssistantUnavailableException;
import com.nfv.assistant.logic.ExtractionInput;
import com.nfv.assistant.logic.StructuredExtraction;
import com.nfv.bookings.entities.AccommodationDetails;
import com.nfv.bookings.entities.Booking;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orders candidates that already meet all criteria by how well they replace the current accommodation, with a
 * reason each (assistant). Only facts of the accommodations go to the model, no data of the travellers. Without
 * assistant, or if it fails, the order stays (by price) and there are no reasons.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AlternativeRanking {
    private static final String INSTRUCTIONS = """
            A traveller cannot use their booked accommodation (e.g. wrong booking, the hotel lost the reservation) \
            and needs a replacement for the same stay. You get the current accommodation and candidates that already \
            meet all hard criteria (price, area, dates, guests, rooms); prices are totals for the stay.
            Rank the candidates by how equivalent they are to the current accommodation and how convenient they are \
            for the traveller: similar category and comfort, close to the current place, good rating, free \
            cancellation, reasonable price. Prefer a clearly better fit over a slightly cheaper one.
            Rules:
            - Use only the given facts, never invent amenities, prices or distances.
            - Return every candidate exactly once, by its hotelId, best first.
            - reason: one short sentence in German for the traveller, e.g. "Gleiche Kategorie, 400 m entfernt, \
            kostenlos stornierbar." No marketing language.""";

    private final StructuredExtraction extraction;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    boolean isAvailable() {
        return extraction.isAvailable();
    }

    /** Candidates in the model's order with reasons; unknown ids are dropped, missing ones keep their place at the end. */
    List<AlternativeCandidate> rank(long appUserId, Booking current, AccommodationDetails details,
                                    List<AlternativeCandidate> candidates) {
        if (!isAvailable() || candidates.isEmpty()) {
            return candidates;
        }
        JsonNode answer;
        try {
            answer = extraction.extract(appUserId, INSTRUCTIONS, "alternative_ranking", schema(),
                    List.of(new ExtractionInput.Text(input(current, details, candidates))));
        } catch (AssistantUnavailableException | AssistantRateLimitedException e) {
            log.info("Ranking of alternatives skipped: {}", e.getMessage());
            return candidates;
        }
        Map<String, AlternativeCandidate> byId = new LinkedHashMap<>();
        candidates.forEach(candidate -> byId.put(candidate.hotelId(), candidate));
        List<AlternativeCandidate> ranked = new ArrayList<>();
        for (JsonNode entry : answer.path("ranking")) {
            AlternativeCandidate candidate = byId.remove(entry.path("hotelId").asString(""));
            if (candidate != null) {
                String reason = entry.path("reason").asString("").strip();
                ranked.add(candidate.withReason(reason.isEmpty() ? null : truncate(reason)));
            }
        }
        ranked.addAll(byId.values());
        return ranked;
    }

    private String input(Booking current, AccommodationDetails details, List<AlternativeCandidate> candidates) {
        ObjectNode input = jsonMapper.createObjectNode();
        ObjectNode currentNode = input.putObject("currentAccommodation");
        currentNode.put("name", details.getName());
        put(currentNode, "address", details.getAddressLine());
        if (current.getPrice() != null) {
            currentNode.put("price", current.getPrice().toPlainString() + " " + current.getCurrency());
        }
        ArrayNode list = input.putArray("candidates");
        for (AlternativeCandidate candidate : candidates) {
            ObjectNode node = list.addObject();
            node.put("hotelId", candidate.hotelId());
            node.put("name", candidate.name());
            node.put("distanceMeters", candidate.distanceMeters());
            node.put("price", candidate.price().toPlainString() + " " + candidate.currency());
            if (candidate.stars() != null) node.put("stars", candidate.stars());
            if (candidate.rating() != null) node.put("rating", candidate.rating());
            put(node, "room", candidate.roomName());
            put(node, "board", candidate.boardName());
            node.put("freeCancellation", candidate.refundable());
            if (candidate.possiblySame()) node.put("possiblyTheCurrentAccommodation", true);
        }
        return jsonMapper.writeValueAsString(input);
    }

    private static void put(ObjectNode node, String field, @Nullable String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static String truncate(String reason) {
        return reason.length() > 300 ? reason.substring(0, 300) : reason;
    }

    private JsonNode schema() {
        return jsonMapper.readTree("""
                {"type": "object", "additionalProperties": false, "required": ["ranking"],
                 "properties": {"ranking": {"type": "array", "items": {"type": "object", "additionalProperties": false,
                   "required": ["hotelId", "reason"],
                   "properties": {"hotelId": {"type": "string"}, "reason": {"type": "string"}}}}}}""");
    }
}
