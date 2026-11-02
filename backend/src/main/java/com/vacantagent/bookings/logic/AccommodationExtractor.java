package com.vacantagent.bookings.logic;

import com.vacantagent.assistant.logic.ExtractionInput;
import com.vacantagent.assistant.logic.StructuredExtraction;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * Reads an accommodation booking from a confirmation (pasted text, screenshot or PDF) with the assistant. The input
 * is passed through and never stored; PIN, payment data and the guest's personal data are not extracted.
 */
@Service
@RequiredArgsConstructor
public class AccommodationExtractor {
    /** Same limit as title photos and the multipart configuration. */
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_TEXT_CHARS = 60_000;
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");
    private static final String PDF = "application/pdf";
    private static final List<String> FIELDS = List.of("name", "addressLine", "countryCode", "phone", "email",
            "website", "provider", "reference", "manageUrl", "checkInDate", "checkInTime", "checkOutDate",
            "checkOutTime", "checkInFrom", "checkInUntil", "checkOutUntil", "guests", "rooms", "price", "currency",
            "cancellationDeadline");

    private static final String INSTRUCTIONS = """
            You read travel booking confirmations (e-mails, app screenshots, PDFs) and extract exactly one \
            accommodation booking (hotel, apartment, holiday home, hostel, campsite, ...).
            Rules:
            - Use only information present in the input, never invent or guess values. Use null if something is missing.
            - Dates as YYYY-MM-DD, times as HH:MM (24 h), always the local time at the accommodation. If the year is \
            missing, take the next occurrence after today (%s).
            - checkInTime/checkOutTime: booked arrival/departure time; if only a window is given, the start of the \
            check-in window and the end of the check-out window. checkInFrom/checkInUntil/checkOutUntil: the house \
            rules for check-in and check-out if stated.
            - provider: platform or company the booking was made with (e.g. Booking.com, Airbnb, Expedia, or the \
            accommodation itself). reference: booking or confirmation number only.
            - NEVER output PIN codes, security codes, passwords, payment card or bank data, or the guest's name, \
            address or contact details. phone/email/website belong to the accommodation, not to the guest or platform \
            support.
            - manageUrl: https link to view or manage this booking, if contained in the input.
            - price: total price as number, currency as ISO 4217 code. countryCode: ISO 3166-1 alpha-2 of the \
            accommodation.
            - cancellationDeadline: last moment of free cancellation, local, YYYY-MM-DDTHH:MM.
            - guests: number of guests, rooms: number of rooms or units.
            - uncertainFields: names of the fields whose value you are not sure about (e.g. ambiguous date formats, \
            partly unreadable screenshot).
            - If the input contains no accommodation booking, set isBooking to false and every other field to null \
            (uncertainFields empty). If it contains several, take the first one.""";

    private final StructuredExtraction extraction;
    private final Clock clock;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public boolean isAvailable() {
        return extraction.isAvailable();
    }

    /**
     * @param text        pasted text or shared text/URL, may be null if there is a file
     * @param contentType of the file: JPEG, PNG, WebP, GIF or PDF
     */
    public AccommodationDraft extract(long appUserId, @Nullable String text, @Nullable String filename,
                                      @Nullable String contentType, @Nullable byte[] file) {
        List<ExtractionInput> inputs = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            if (text.length() > MAX_TEXT_CHARS) {
                throw new InvalidBookingInputException("text has at most " + MAX_TEXT_CHARS + " characters");
            }
            inputs.add(new ExtractionInput.Text(text.strip()));
        }
        if (file != null && file.length > 0) {
            if (file.length > MAX_FILE_BYTES) {
                throw new InvalidBookingInputException("file must have at most 5 MB");
            }
            String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
            if (IMAGE_TYPES.contains(type)) {
                inputs.add(new ExtractionInput.Image(type, file));
            } else if (PDF.equals(type)) {
                inputs.add(new ExtractionInput.Pdf(filename == null || filename.isBlank() ? "booking.pdf" : filename, file));
            } else {
                throw new InvalidBookingInputException("file must be an image (JPEG, PNG, WebP, GIF) or a PDF");
            }
        }
        if (inputs.isEmpty()) {
            throw new InvalidBookingInputException("text or file is required");
        }
        JsonNode answer = extraction.extract(appUserId, INSTRUCTIONS.formatted(LocalDate.now(clock)),
                "accommodation_booking", schema(), inputs);
        return toDraft(answer);
    }

    AccommodationDraft toDraft(JsonNode answer) {
        if (!answer.path("isBooking").asBoolean(false)) {
            return AccommodationDraft.notFound();
        }
        Set<String> uncertain = new LinkedHashSet<>();
        for (JsonNode field : answer.path("uncertainFields")) {
            if (FIELDS.contains(field.asString(""))) {
                uncertain.add(field.asString());
            }
        }
        Parser p = new Parser(answer, uncertain);
        String countryCode = p.text("countryCode");
        if (countryCode != null && !countryCode.matches("[A-Za-z]{2}")) {
            countryCode = null;
        }
        String currency = p.text("currency");
        if (currency != null && !currency.matches("[A-Za-z]{3}")) {
            currency = null;
            uncertain.add("currency");
        }
        BigDecimal price = p.parse("price", value -> new BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_UP));
        if (price != null && price.signum() < 0) {
            price = null;
        }
        return new AccommodationDraft(true, p.text("name"), p.text("addressLine"),
                countryCode == null ? null : countryCode.toUpperCase(Locale.ROOT), p.text("phone"), p.text("email"),
                p.https("website"), p.text("provider"), p.text("reference"), p.https("manageUrl"),
                p.parse("checkInDate", LocalDate::parse), p.parse("checkInTime", LocalTime::parse),
                p.parse("checkOutDate", LocalDate::parse), p.parse("checkOutTime", LocalTime::parse),
                p.parse("checkInFrom", LocalTime::parse), p.parse("checkInUntil", LocalTime::parse),
                p.parse("checkOutUntil", LocalTime::parse), p.count("guests"), p.count("rooms"), price,
                currency == null ? null : currency.toUpperCase(Locale.ROOT),
                p.parse("cancellationDeadline", LocalDateTime::parse), List.copyOf(uncertain));
    }

    /** Lenient reading of the answer: a value in the wrong format becomes null and is marked uncertain. */
    private record Parser(JsonNode answer, Set<String> uncertain) {
        @Nullable String text(String field) {
            JsonNode node = answer.path(field);
            if (!node.isString() || node.asString().isBlank()) {
                return null;
            }
            String value = node.asString().strip();
            return value.length() > 255 && !field.equals("addressLine") ? value.substring(0, 255) : value;
        }

        @Nullable String https(String field) {
            String value = text(field);
            return value != null && value.startsWith("https://") ? value : null;
        }

        @Nullable Integer count(String field) {
            JsonNode node = answer.path(field);
            if (!node.isNumber()) {
                return null;
            }
            int value = node.asInt();
            return value >= 1 && value <= 99 ? value : null;
        }

        <T> @Nullable T parse(String field, Function<String, T> parser) {
            JsonNode node = answer.path(field);
            String value = node.isNumber() ? node.asString() : text(field);
            if (value == null) {
                return null;
            }
            try {
                return parser.apply(value);
            } catch (DateTimeParseException | NumberFormatException | ArithmeticException e) {
                uncertain.add(field);
                return null;
            }
        }
    }

    /** Strict structured-output schema: every property required, null where unknown. */
    private JsonNode schema() {
        StringBuilder properties = new StringBuilder("\"isBooking\": {\"type\": \"boolean\"}");
        for (String field : FIELDS) {
            String type = switch (field) {
                case "guests", "rooms" -> "[\"integer\", \"null\"]";
                case "price" -> "[\"number\", \"null\"]";
                default -> "[\"string\", \"null\"]";
            };
            properties.append(", \"").append(field).append("\": {\"type\": ").append(type).append('}');
        }
        properties.append(", \"uncertainFields\": {\"type\": \"array\", \"items\": {\"type\": \"string\", \"enum\": [")
                .append(String.join(", ", FIELDS.stream().map(field -> '"' + field + '"').toList()))
                .append("]}}");
        List<String> required = new ArrayList<>(List.of("isBooking"));
        required.addAll(FIELDS);
        required.add("uncertainFields");
        String schema = "{\"type\": \"object\", \"additionalProperties\": false, \"properties\": {" + properties
                + "}, \"required\": [" + String.join(", ", required.stream().map(field -> '"' + field + '"').toList())
                + "]}";
        return jsonMapper.readTree(schema);
    }
}
