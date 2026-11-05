package com.nfv.bookings.logic;

import com.nfv.assistant.logic.AssistantRateLimitedException;
import com.nfv.assistant.logic.AssistantUnavailableException;
import com.nfv.assistant.logic.ExtractionInput;
import com.nfv.assistant.logic.StructuredExtraction;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Helps entering an accommodation booking by hand: suggestions for the name (LiteAPI hotel data, near the stop
 * first) and the facts of the chosen accommodation. Facts come from LiteAPI where it has them; what is missing the
 * assistant looks up on the web. Only public data of the accommodation is sent, nothing about the user or the booking.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccommodationLookup {
    static final int RADIUS_METERS = 30_000;
    static final int MAX_SUGGESTIONS = 8;
    private static final List<String> TIME_FIELDS = List.of("checkInFrom", "checkInUntil", "checkOutUntil");

    private static final String INSTRUCTIONS = """
            You look up public facts about one accommodation (hotel, apartment, hostel, campsite, ...) on the web: \
            the official website and booking platforms such as the hotel's own site, Booking.com, Expedia, Tripadvisor \
            or Google Business listings.
            Rules:
            - Look up only the fields listed as missing, give null for all others.
            - Make sure the source is about exactly this accommodation (name and address or town match). If you \
            cannot identify it with confidence, give null for everything.
            - Never guess or invent values. A value you did not find stated in a source is null.
            - Times as HH:MM (24 h), local time at the accommodation. checkInFrom: earliest check-in, checkInUntil: \
            latest check-in, checkOutUntil: latest check-out.
            - phone in international format (+...), email and website of the accommodation itself, not of a booking \
            platform. website as https URL.
            - sources: the URLs you took the values from (at most 3).""";

    private final LiteApi liteApi;
    private final StructuredExtraction assistant;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    /**
     * Accommodations whose name contains the query; with a point (the stop) those within 30 km first, nearest
     * first. Empty if LiteAPI is not configured or not reachable, the client then offers only map results.
     */
    public List<AccommodationSuggestion> suggest(String query, @Nullable Double latitude, @Nullable Double longitude) {
        String name = query == null ? "" : query.strip();
        if (name.length() < 3 || !liteApi.isAvailable()) {
            return List.of();
        }
        if (name.length() > 100) {
            throw new InvalidBookingInputException("query has at most 100 characters");
        }
        List<LiteApi.HotelMatch> matches = new ArrayList<>();
        try {
            if (latitude != null && longitude != null) {
                matches.addAll(liteApi.searchHotels(name, latitude, longitude, RADIUS_METERS, MAX_SUGGESTIONS));
                matches.sort(Comparator.comparingDouble(match -> distance(match, latitude, longitude)));
            }
            if (matches.size() < MAX_SUGGESTIONS) {
                Set<String> seen = new LinkedHashSet<>(matches.stream().map(LiteApi.HotelMatch::hotelId).toList());
                liteApi.searchHotels(name, null, null, 0, MAX_SUGGESTIONS).stream()
                        .filter(match -> seen.add(match.hotelId()))
                        .forEach(matches::add);
            }
        } catch (LiteApiUnavailableException e) {
            log.info("No accommodation suggestions from LiteAPI: {}", e.getMessage());
        }
        return matches.stream().limit(MAX_SUGGESTIONS)
                .map(match -> new AccommodationSuggestion(match.hotelId(), match.name(),
                        addressLine(match.address(), match.city()), match.countryCode(), match.latitude(),
                        match.longitude(), match.stars()))
                .toList();
    }

    /**
     * Contact and house rules of a chosen accommodation. LiteAPI first (with its id), the assistant fills what is
     * still missing if it is configured; without both the answer is empty.
     */
    public AccommodationFacts facts(long appUserId, AccommodationQuery query) {
        if (query.name() == null || query.name().isBlank()) {
            throw new InvalidBookingInputException("name is required");
        }
        Values values = new Values();
        if (query.hotelId() != null && !query.hotelId().isBlank() && liteApi.isAvailable()) {
            try {
                LiteApi.HotelDetails hotel = liteApi.hotel(query.hotelId());
                values.fromLiteApi(Field.ADDRESS_LINE, addressLine(hotel.address(), hotel.city()));
                values.fromLiteApi(Field.COUNTRY_CODE, hotel.countryCode());
                values.fromLiteApi(Field.PHONE, hotel.phone());
                values.fromLiteApi(Field.EMAIL, hotel.email());
                values.fromLiteApi(Field.WEBSITE, hotel.website());
                values.fromLiteApi(Field.CHECK_IN_FROM, hotel.checkInFrom());
                values.fromLiteApi(Field.CHECK_IN_UNTIL, hotel.checkInUntil());
                values.fromLiteApi(Field.CHECK_OUT_UNTIL, hotel.checkOutUntil());
            } catch (LiteApiUnavailableException e) {
                log.info("No hotel data from LiteAPI: {}", e.getMessage());
            }
        }
        Set<Field> missing = values.missing();
        missing.removeAll(EnumSet.of(Field.ADDRESS_LINE, Field.COUNTRY_CODE));
        List<String> sources = List.of();
        boolean researched = false;
        if (!missing.isEmpty() && assistant.isAvailable()) {
            try {
                JsonNode answer = assistant.extract(appUserId, INSTRUCTIONS, "accommodation_facts", schema(),
                        List.of(new ExtractionInput.Text(prompt(query, values, missing))), true);
                researched = true;
                for (Field field : missing) {
                    values.fromAssistant(field, answer.path(field.json));
                }
                sources = new ArrayList<>();
                for (JsonNode source : answer.path("sources")) {
                    String url = source.asString("");
                    if (url.startsWith("https://") && url.length() <= 1000 && sources.size() < 3) {
                        sources.add(url);
                    }
                }
            } catch (AssistantUnavailableException | AssistantRateLimitedException e) {
                log.info("Assistant could not look up the accommodation: {}", e.getMessage());
            }
        }
        return values.facts(researched, List.copyOf(sources));
    }

    private String prompt(AccommodationQuery query, Values values, Set<Field> missing) {
        StringBuilder text = new StringBuilder("Accommodation: ").append(query.name().strip());
        String address = values.text(Field.ADDRESS_LINE) != null ? values.text(Field.ADDRESS_LINE) : query.addressLine();
        if (address != null && !address.isBlank()) {
            text.append("\nAddress: ").append(address.strip());
        }
        String country = values.text(Field.COUNTRY_CODE) != null ? values.text(Field.COUNTRY_CODE) : query.countryCode();
        if (country != null && !country.isBlank()) {
            text.append("\nCountry: ").append(country.strip());
        }
        if (query.latitude() != null && query.longitude() != null) {
            text.append("\nCoordinates: ").append(query.latitude()).append(", ").append(query.longitude());
        }
        text.append("\nMissing fields: ").append(String.join(", ", missing.stream().map(f -> f.json).toList()));
        return text.toString();
    }

    private JsonNode schema() {
        ObjectNode schema = jsonMapper.createObjectNode();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        ObjectNode properties = schema.putObject("properties");
        var required = schema.putArray("required");
        for (Field field : Field.values()) {
            if (field == Field.ADDRESS_LINE || field == Field.COUNTRY_CODE) {
                continue;
            }
            ObjectNode property = properties.putObject(field.json);
            property.putArray("type").add("string").add("null");
            if (field.time) {
                property.put("description", "HH:MM, 24 h");
            }
            required.add(field.json);
        }
        ObjectNode sources = properties.putObject("sources");
        sources.put("type", "array");
        sources.putObject("items").put("type", "string");
        required.add("sources");
        return schema;
    }

    static @Nullable String addressLine(@Nullable String address, @Nullable String city) {
        if (address == null) {
            return city;
        }
        return city == null || address.toLowerCase().contains(city.toLowerCase()) ? address : address + ", " + city;
    }

    private static double distance(LiteApi.HotelMatch match, double latitude, double longitude) {
        if (match.latitude() == null || match.longitude() == null) {
            return Double.MAX_VALUE;
        }
        double dLat = Math.toRadians(match.latitude() - latitude);
        double dLon = Math.toRadians(match.longitude() - longitude) * Math.cos(Math.toRadians(latitude));
        return dLat * dLat + dLon * dLon;
    }

    /** Fields of the facts, with their name in the JSON of the assistant and of the API. */
    enum Field {
        ADDRESS_LINE("addressLine", false), COUNTRY_CODE("countryCode", false), PHONE("phone", false),
        EMAIL("email", false), WEBSITE("website", false), CHECK_IN_FROM("checkInFrom", true),
        CHECK_IN_UNTIL("checkInUntil", true), CHECK_OUT_UNTIL("checkOutUntil", true);

        final String json;
        final boolean time;

        Field(String json, boolean time) {
            this.json = json;
            this.time = time;
        }
    }

    /** Collected values with their origin. */
    private static final class Values {
        private final java.util.EnumMap<Field, Object> values = new java.util.EnumMap<>(Field.class);
        private final Set<Field> fromLiteApi = EnumSet.noneOf(Field.class);
        private final Set<Field> fromAssistant = EnumSet.noneOf(Field.class);

        void fromLiteApi(Field field, @Nullable Object value) {
            if (value != null && !(value instanceof String text && text.isBlank())) {
                values.put(field, value);
                fromLiteApi.add(field);
            }
        }

        void fromAssistant(Field field, JsonNode node) {
            if (!node.isString() || node.asString().isBlank()) {
                return;
            }
            String text = node.asString().strip();
            Object value = switch (field) {
                case CHECK_IN_FROM, CHECK_IN_UNTIL, CHECK_OUT_UNTIL -> LiteApi.time(text);
                case WEBSITE -> text.startsWith("https://") && text.length() <= 1000 ? text : null;
                case EMAIL -> text.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") && text.length() <= 200 ? text : null;
                case PHONE -> text.matches("[+0-9 ()/.-]{5,40}") ? text : null;
                default -> text.length() <= 300 ? text : null;
            };
            if (value != null) {
                values.put(field, value);
                fromAssistant.add(field);
            }
        }

        Set<Field> missing() {
            Set<Field> missing = EnumSet.allOf(Field.class);
            missing.removeAll(values.keySet());
            return missing;
        }

        @Nullable String text(Field field) {
            return (String) values.get(field);
        }

        @Nullable LocalTime time(Field field) {
            return (LocalTime) values.get(field);
        }

        AccommodationFacts facts(boolean researched, List<String> sources) {
            return new AccommodationFacts(text(Field.ADDRESS_LINE), text(Field.COUNTRY_CODE), text(Field.PHONE),
                    text(Field.EMAIL), text(Field.WEBSITE), time(Field.CHECK_IN_FROM), time(Field.CHECK_IN_UNTIL),
                    time(Field.CHECK_OUT_UNTIL), names(fromLiteApi), names(fromAssistant), researched, sources);
        }

        private static List<String> names(Set<Field> fields) {
            return fields.stream().map(field -> field.json).toList();
        }
    }
}
