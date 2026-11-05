package com.nfv.input.sources.nz;

import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.logic.IncidentLocation;
import com.nfv.input.logic.TrafficReport;
import jakarta.annotation.Nullable;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads one NZTA {@code roadevent}, field table and type rules in Docs/traffic-feed.md#erste-quelle-nzta.
 * Direction is left open (both directions): the suffix in {@code locations} does not match the text reliably, the
 * direction comes from the enrichment later. A local road ({@code SH N/A}) has no road number; events on a ramp or
 * link alone are marked {@code linkOnly}.
 */
final class NztaReader {
    static final ZoneId ZONE = ZoneId.of("Pacific/Auckland");
    /** Keys of {@code nfv.input.sources.nz-nzta.assumed-delay.*} and their defaults. */
    static final Map<String, Duration> DEFAULT_ASSUMED_DELAYS = Map.of(
            "caution", Duration.ZERO,
            "delays", Duration.ofMinutes(15),
            "crash", Duration.ofMinutes(30),
            "vehicle-restrictions", Duration.ZERO);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final DateTimeFormatter EXPECTED_RESOLUTION = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    /** Texts NZTA puts into {@code alternativeRoute} when there is none, compared in lower case without final dot. */
    private static final Set<String> NO_ALTERNATIVE = Set.of("not applicable", "n/a");
    /** Only a ramp or link; "incl link to" is the road itself plus its link. */
    private static final Pattern LINK_ONLY = Pattern.compile("(?i)\\b(on|off)[- ]?ramps?\\b|(?<!incl )\\blinks? to\\b");
    /** {@code locationArea} of a local road, {@code way} then only names a highway nearby. */
    private static final String LOCAL_ROAD = "SH N/A";

    private final Map<String, Duration> assumedDelays;

    NztaReader(Map<String, Duration> configured) {
        Map<String, Duration> delays = new HashMap<>(DEFAULT_ASSUMED_DELAYS);
        delays.putAll(configured);
        this.assumedDelays = Map.copyOf(delays);
    }

    TrafficReport read(String payload) {
        JsonNode event;
        try {
            event = MAPPER.readTree(payload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Unreadable NZTA event: " + e.getOriginalMessage(), e);
        }
        if (event == null || !event.isObject()) {
            throw new IllegalArgumentException("NZTA event is not an object");
        }
        String impact = Objects.requireNonNullElse(text(event, "impact"), "");
        String description = Objects.requireNonNullElse(text(event, "eventDescription"), "");
        String eventType = Objects.requireNonNullElse(text(event, "eventType"), "");
        OffsetDateTime validFrom = time(event, "startDate");
        if (validFrom == null) {
            validFrom = time(event, "eventCreated");
        }
        if (validFrom == null) {
            throw new IllegalArgumentException("NZTA event without startDate and eventCreated");
        }
        String messageText = messageText(event);
        String locationArea = text(event, "locationArea");
        String title = Stream.of(eventType.isEmpty() ? null : eventType, locationArea)
                .filter(Objects::nonNull).collect(Collectors.joining(": "));
        String roadRef = locationArea != null && locationArea.startsWith(LOCAL_ROAD) ? null
                : text(event.path("way"), "name");
        boolean linkOnly = locationArea != null && LINK_ONLY.matcher(locationArea).find();
        return new TrafficReport(type(impact, description, eventType), "Resolved".equals(text(event, "status")),
                validFrom, validTo(event), roadRef, null, location(text(event, "geometry")),
                null, assumedDelay(impact, description), title.isEmpty() ? "NZTA" : title,
                description(messageText, locations(event.get("locations"))), messageText, null, linkOnly, List.of(), null);
    }

    /** Type rules, first match wins. */
    static IncidentType type(String impact, String description, String eventType) {
        if (impact.equals("Road Closed")) {
            return IncidentType.CLOSURE;
        }
        if (description.equals("Crash")) {
            return IncidentType.ACCIDENT;
        }
        if (eventType.equals("Road Work") || eventType.equals("Scheduled Road Work")) {
            return IncidentType.ROADWORKS;
        }
        if (impact.equals("Delays")) {
            return IncidentType.CONGESTION;
        }
        return IncidentType.OTHER;
    }

    /** Assumption without a figure: none for closures, the crash value before the impact values. */
    @Nullable Duration assumedDelay(String impact, String description) {
        if (impact.equals("Road Closed")) {
            return null;
        }
        if (description.equals("Crash")) {
            return assumedDelays.get("crash");
        }
        return switch (impact) {
            case "Delays" -> assumedDelays.get("delays");
            case "Caution" -> assumedDelays.get("caution");
            case "Vehicle Restrictions" -> assumedDelays.get("vehicle-restrictions");
            default -> null;
        };
    }

    /** {@code endDate}, otherwise {@code expectedResolution} in local time; {@code Until further notice} → open. */
    private static @Nullable OffsetDateTime validTo(JsonNode event) {
        OffsetDateTime endDate = time(event, "endDate");
        if (endDate != null) {
            return endDate;
        }
        String expected = text(event, "expectedResolution");
        if (expected == null || expected.equalsIgnoreCase("Until further notice")) {
            return null;
        }
        try {
            return LocalDateTime.parse(expected, EXPECTED_RESOLUTION).atZone(ZONE).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** WKT in WGS84 (x = longitude): point, (multi)line, polygon as area. */
    static @Nullable IncidentLocation location(@Nullable String wkt) {
        if (wkt == null) {
            return null;
        }
        Geometry geometry;
        try {
            geometry = new WKTReader().read(wkt);
        } catch (ParseException e) {
            throw new IllegalArgumentException("Unreadable NZTA geometry: " + e.getMessage(), e);
        }
        if (geometry instanceof Point point) {
            return new IncidentLocation.Point(coordinate(point.getCoordinate()));
        }
        if (geometry instanceof Polygon polygon) {
            return new IncidentLocation.Area(coordinates(polygon.getExteriorRing().getCoordinates()));
        }
        List<List<IncidentLocation.Coordinate>> lines = new ArrayList<>();
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            if (geometry.getGeometryN(i) instanceof LineString line && line.getNumPoints() > 0) {
                lines.add(coordinates(line.getCoordinates()));
            }
        }
        return lines.isEmpty() ? null : new IncidentLocation.Lines(lines);
    }

    /** {@code locationArea}, {@code eventComments} and {@code alternativeRoute} (without placeholders), one per line. */
    static @Nullable String messageText(JsonNode event) {
        String alternative = text(event, "alternativeRoute");
        if (alternative != null) {
            String lower = alternative.toLowerCase(Locale.ROOT);
            if (NO_ALTERNATIVE.contains(lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower)) {
                alternative = null;
            }
        }
        String text = Stream.of(text(event, "locationArea"), text(event, "eventComments"), alternative)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));
        return text.isEmpty() ? null : text;
    }

    /** {@code locations} is mostly one text, sometimes a list of texts. */
    static List<String> locations(@Nullable JsonNode locations) {
        if (locations == null || locations.isNull()) {
            return List.of();
        }
        Stream<JsonNode> values = locations.isArray() ? locations.values().stream() : Stream.of(locations);
        return values.filter(JsonNode::isString).map(node -> node.asString().strip())
                .filter(value -> !value.isEmpty()).toList();
    }

    private static @Nullable String description(@Nullable String messageText, List<String> locations) {
        String text = Stream.concat(Stream.ofNullable(messageText), locations.stream())
                .collect(Collectors.joining("\n"));
        return text.isEmpty() ? null : text;
    }

    private static @Nullable OffsetDateTime time(JsonNode event, String field) {
        String value = text(event, field);
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Unreadable NZTA time " + field + ": " + value, e);
        }
    }

    private static IncidentLocation.Coordinate coordinate(Coordinate coordinate) {
        return new IncidentLocation.Coordinate(coordinate.getY(), coordinate.getX());
    }

    private static List<IncidentLocation.Coordinate> coordinates(Coordinate[] coordinates) {
        return Arrays.stream(coordinates).map(NztaReader::coordinate).toList();
    }

    static @Nullable String text(JsonNode event, String field) {
        JsonNode node = event.get(field);
        if (node == null || !node.isString()) {
            return null;
        }
        String value = node.asString().strip();
        return value.isEmpty() ? null : value;
    }
}
