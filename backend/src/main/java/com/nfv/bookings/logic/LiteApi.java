package com.nfv.bookings.logic;

import com.nfv.bookings.config.AlternativesProperties;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Technical connection to LiteAPI (Nuitee): rates, hotel data, prebook, book. Answers with own records, no LiteAPI
 * types leave this class. 4xx answers to prebook and book are {@link OfferUnavailableException} (no effect), 5xx and
 * I/O errors {@link LiteApiUnavailableException} (outcome unknown), see Docs/accommodation-alternatives.md.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiteApi {
    private final RestClient liteApiRestClient;
    private final AlternativesProperties properties;
    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    public boolean isAvailable() {
        return properties.isConfigured();
    }

    /** Net rates (margin 0) of the hotels around a point with their hotel data. */
    public List<HotelOffer> rates(RateQuery query) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("latitude", query.latitude());
        body.put("longitude", query.longitude());
        body.put("radius", query.radiusMeters());
        body.put("checkin", query.checkIn().toString());
        body.put("checkout", query.checkOut().toString());
        body.put("currency", query.currency());
        body.put("guestNationality", properties.guestNationality());
        body.put("margin", 0);
        body.put("includeHotelData", true);
        body.put("maxRatesPerHotel", 3);
        body.put("limit", 200);
        body.put("timeout", 10);
        ArrayNode occupancies = body.putArray("occupancies");
        for (int adults : query.adultsPerRoom()) {
            occupancies.addObject().put("adults", adults).putArray("children");
        }
        JsonNode answer = post(properties.searchUrl() + "/hotels/rates", body, false);

        Map<String, JsonNode> hotels = new HashMap<>();
        for (JsonNode hotel : answer.path("hotels")) {
            hotels.put(hotel.path("id").asString(""), hotel);
        }
        List<HotelOffer> offers = new ArrayList<>();
        for (JsonNode entry : answer.path("data")) {
            String hotelId = entry.path("hotelId").asString("");
            JsonNode hotel = hotels.get(hotelId);
            if (hotel == null || !hotel.path("latitude").isNumber()) {
                continue;
            }
            for (JsonNode roomType : entry.path("roomTypes")) {
                Amount total = amount(roomType.path("offerRetailRate"));
                String offerId = text(roomType.path("offerId"));
                if (total == null || offerId == null) {
                    continue;
                }
                JsonNode firstRate = roomType.path("rates").path(0);
                JsonNode cancellation = firstRate.path("cancellationPolicies");
                offers.add(new HotelOffer(hotelId, text(hotel.path("name")) == null ? hotelId : text(hotel.path("name")),
                        text(hotel.path("address")), text(hotel.path("country_code")),
                        hotel.path("latitude").asDouble(), hotel.path("longitude").asDouble(),
                        number(hotel.path("stars")), number(hotel.path("rating")), text(hotel.path("main_photo")),
                        offerId, text(firstRate.path("name")), text(firstRate.path("boardName")),
                        "RFN".equals(cancellation.path("refundableTag").asString("")),
                        freeCancellationUntil(cancellation), total.value(), total.currency(),
                        amountOrNull(roomType.path("suggestedSellingPrice"), total.currency())));
            }
        }
        return offers;
    }

    /**
     * Hotels whose name contains the query, for the autocompletion when entering a booking. With a point, only
     * hotels within the radius around it, nearest first.
     */
    public List<HotelMatch> searchHotels(String name, @Nullable Double latitude, @Nullable Double longitude,
                                         int radiusMeters, int limit) {
        StringBuilder url = new StringBuilder(properties.searchUrl()).append("/data/hotels?hotelName={name}&limit={limit}");
        List<Object> variables = new ArrayList<>(List.of(name, limit));
        if (latitude != null && longitude != null) {
            url.append("&latitude={lat}&longitude={lon}&radius={radius}");
            variables.addAll(List.of(latitude, longitude, radiusMeters));
        }
        JsonNode answer = get(url.toString(), variables.toArray());
        List<HotelMatch> matches = new ArrayList<>();
        for (JsonNode hotel : answer.path("data")) {
            String id = text(hotel.path("id"));
            String hotelName = text(hotel.path("name"));
            if (id == null || hotelName == null) {
                continue;
            }
            String country = text(hotel.path("country"));
            matches.add(new HotelMatch(id, hotelName, text(hotel.path("address")), text(hotel.path("city")),
                    country != null && country.matches("[A-Za-z]{2}") ? country.toUpperCase() : null,
                    hotel.path("latitude").isNumber() ? hotel.path("latitude").asDouble() : null,
                    hotel.path("longitude").isNumber() ? hotel.path("longitude").asDouble() : null,
                    number(hotel.path("stars"))));
        }
        return matches;
    }

    /** Hotel data for the booking: contact, address, check-in times. */
    public HotelDetails hotel(String hotelId) {
        JsonNode data = get(properties.searchUrl() + "/data/hotel?hotelId={id}", hotelId).path("data");
        JsonNode times = data.path("checkinCheckoutTimes");
        String country = text(data.path("country"));
        return new HotelDetails(hotelId, text(data.path("name")) == null ? hotelId : text(data.path("name")),
                text(data.path("address")), text(data.path("city")),
                country != null && country.matches("[A-Za-z]{2}") ? country.toUpperCase() : null,
                data.path("location").path("latitude").isNumber() ? data.path("location").path("latitude").asDouble() : null,
                data.path("location").path("longitude").isNumber() ? data.path("location").path("longitude").asDouble() : null,
                text(data.path("phone")), text(data.path("email")), https(text(data.path("website"))),
                time(firstText(times, "checkin_start", "checkin")), time(text(times.path("checkin_end"))),
                time(text(times.path("checkout"))));
    }

    /** Locks price and availability of an offer, to be paid by ourselves (no payment SDK). */
    public Prebook prebook(String offerId) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("offerId", offerId);
        body.put("usePaymentSdk", false);
        JsonNode data = post(properties.bookUrl() + "/rates/prebook", body, true).path("data");
        String prebookId = text(data.path("prebookId"));
        BigDecimal price = decimal(data.path("price"));
        String currency = text(data.path("currency"));
        if (prebookId == null || price == null || currency == null) {
            throw new LiteApiUnavailableException("prebook answer without id or price");
        }
        JsonNode cancellation = data.path("roomTypes").path(0).path("rates").path(0).path("cancellationPolicies");
        return new Prebook(prebookId, price, currency,
                amountOrNull(data.path("suggestedSellingPrice"), currency),
                data.path("cancellationChanged").asBoolean(false) || data.path("boardChanged").asBoolean(false),
                "RFN".equals(cancellation.path("refundableTag").asString("")), freeCancellationUntil(cancellation));
    }

    /**
     * Books a prebooked offer, charged to the card of our LiteAPI account ({@code ACC_CREDIT_CARD}).
     *
     * @param clientReference our id, LiteAPI refuses a second booking with it
     */
    public ProviderBooking book(String prebookId, Holder holder, int rooms, String clientReference) {
        ObjectNode body = jsonMapper.createObjectNode();
        body.put("prebookId", prebookId);
        body.put("clientReference", clientReference);
        ObjectNode holderNode = body.putObject("holder");
        holderNode.put("firstName", holder.firstName());
        holderNode.put("lastName", holder.lastName());
        holderNode.put("email", holder.email());
        holderNode.put("phone", holder.phone());
        ArrayNode guests = body.putArray("guests");
        for (int room = 1; room <= rooms; room++) {
            guests.addObject().put("occupancyNumber", room).put("firstName", holder.firstName())
                    .put("lastName", holder.lastName()).put("email", holder.email());
        }
        body.putObject("payment").put("method", "ACC_CREDIT_CARD");
        JsonNode data = post(properties.bookUrl() + "/rates/book", body, true).path("data");
        String bookingId = text(data.path("bookingId"));
        if (bookingId == null) {
            throw new LiteApiUnavailableException("book answer without booking id");
        }
        return new ProviderBooking(bookingId, text(data.path("hotelConfirmationCode")),
                data.path("status").asString(""));
    }

    private JsonNode post(String url, JsonNode body, boolean offer) {
        requireConfigured();
        try {
            String answer = liteApiRestClient.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(jsonMapper.writeValueAsString(body))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (_, response) -> {
                        throw error(url, response.getStatusCode(), response.getBody().readAllBytes(), offer);
                    })
                    .body(String.class);
            return parse(answer);
        } catch (RestClientException e) {
            log.warn("LiteAPI request failed: {}", e.getMessage());
            throw new LiteApiUnavailableException("LiteAPI request failed");
        }
    }

    private JsonNode get(String url, Object... variables) {
        requireConfigured();
        try {
            String answer = liteApiRestClient.get().uri(url, variables)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (_, response) -> {
                        throw error(url, response.getStatusCode(), response.getBody().readAllBytes(), false);
                    })
                    .body(String.class);
            return parse(answer);
        } catch (RestClientException e) {
            log.warn("LiteAPI request failed: {}", e.getMessage());
            throw new LiteApiUnavailableException("LiteAPI request failed");
        }
    }

    private void requireConfigured() {
        if (!isAvailable()) {
            throw new LiteApiUnavailableException("LiteAPI is not configured");
        }
    }

    private JsonNode parse(@Nullable String answer) {
        try {
            return jsonMapper.readTree(answer == null ? "{}" : answer);
        } catch (JacksonException e) {
            throw new LiteApiUnavailableException("LiteAPI answer is not JSON");
        }
    }

    /** {@code {"error": {"code": 4002, "description": "...", "message": "..."}}} */
    private RuntimeException error(String url, HttpStatusCode status, byte[] body, boolean offer) {
        String code = "?";
        String description = "";
        try {
            JsonNode error = jsonMapper.readTree(body).path("error");
            code = error.path("code").asString("?");
            description = error.path("description").asString(error.path("message").asString(""));
        } catch (JacksonException e) {
            // keep defaults
        }
        log.warn("LiteAPI answered {} on {} (code {}): {}", status.value(), url.replaceAll("\\?.*", ""), code, description);
        if (offer && status.is4xxClientError() && status.value() != 401 && status.value() != 403) {
            return new OfferUnavailableException(code, description);
        }
        return new LiteApiUnavailableException("LiteAPI answered " + status.value() + " (code " + code + ")");
    }

    /** Earliest cancel time with a fee, i.e. free cancellation until then; null if none or unreadable. */
    private static @Nullable LocalDateTime freeCancellationUntil(JsonNode cancellation) {
        LocalDateTime earliest = null;
        for (JsonNode info : cancellation.path("cancelPolicyInfos")) {
            String value = text(info.path("cancelTime"));
            if (value == null) {
                continue;
            }
            try {
                LocalDateTime time = LocalDateTime.parse(value.replace(' ', 'T').replaceAll("(Z|[+-]\\d\\d:?\\d\\d)$", ""));
                if (earliest == null || time.isBefore(earliest)) {
                    earliest = time;
                }
            } catch (DateTimeParseException e) {
                // unknown format, the user still sees whether it is refundable
            }
        }
        return earliest;
    }

    /** Amount as object {@code {amount, currency}} or as array of those (the schema and examples differ). */
    private static @Nullable Amount amount(JsonNode node) {
        JsonNode value = node.isArray() ? node.path(0) : node;
        BigDecimal amount = decimal(value.path("amount"));
        String currency = text(value.path("currency"));
        return amount == null || currency == null ? null : new Amount(amount, currency);
    }

    private static @Nullable BigDecimal amountOrNull(JsonNode node, String currency) {
        Amount amount = amount(node);
        return amount != null && amount.currency().equalsIgnoreCase(currency) ? amount.value() : null;
    }

    private static @Nullable BigDecimal decimal(JsonNode node) {
        if (node.isNumber()) {
            return node.decimalValue();
        }
        try {
            return node.isString() ? new BigDecimal(node.asString()) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static @Nullable Double number(JsonNode node) {
        return node.isNumber() ? node.asDouble() : null;
    }

    private static @Nullable String text(JsonNode node) {
        if (!node.isValueNode() || node.isNull()) {
            return null;
        }
        String value = node.asString("").strip();
        return value.isEmpty() ? null : value;
    }

    private static @Nullable String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node.path(field));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static @Nullable String https(@Nullable String url) {
        return url != null && url.startsWith("https://") && url.length() <= 1000 ? url : null;
    }

    /** "14:00", "9:30", "14:00:00", "03:00 PM", "12:00 AM"; null for anything else (e.g. "flexible"). */
    static @Nullable LocalTime time(@Nullable String value) {
        if (value == null) {
            return null;
        }
        Matcher matcher = TIME.matcher(value.strip());
        if (!matcher.matches()) {
            return null;
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        String half = matcher.group(3);
        if (half != null) {
            if (hour < 1 || hour > 12) {
                return null;
            }
            hour = hour % 12 + (half.equalsIgnoreCase("pm") ? 12 : 0);
        }
        return hour > 23 || minute > 59 ? null : LocalTime.of(hour, minute);
    }

    private static final Pattern TIME = Pattern.compile("(\\d{1,2})[:.](\\d{2})(?::\\d{2})?\\s*([AaPp][Mm])?");

    private record Amount(BigDecimal value, String currency) {
    }

    /** @param adultsPerRoom one entry per room */
    public record RateQuery(double latitude, double longitude, int radiusMeters, LocalDate checkIn, LocalDate checkOut,
                            List<Integer> adultsPerRoom, String currency) {
    }

    /**
     * Cheapest kind of offer of a hotel for all rooms, net.
     *
     * @param freeCancellationUntil local at the hotel, as LiteAPI states it
     */
    public record HotelOffer(String hotelId, String name, @Nullable String address, @Nullable String countryCode,
                             double latitude, double longitude, @Nullable Double stars, @Nullable Double rating,
                             @Nullable String photoUrl, String offerId, @Nullable String roomName,
                             @Nullable String boardName, boolean refundable,
                             @Nullable LocalDateTime freeCancellationUntil, BigDecimal netPrice, String currency,
                             @Nullable BigDecimal suggestedSellingPrice) {
    }

    public record HotelMatch(String hotelId, String name, @Nullable String address, @Nullable String city,
                             @Nullable String countryCode, @Nullable Double latitude, @Nullable Double longitude,
                             @Nullable Double stars) {
    }

    public record HotelDetails(String hotelId, String name, @Nullable String address, @Nullable String city,
                               @Nullable String countryCode, @Nullable Double latitude, @Nullable Double longitude,
                               @Nullable String phone, @Nullable String email, @Nullable String website,
                               @Nullable LocalTime checkInFrom, @Nullable LocalTime checkInUntil,
                               @Nullable LocalTime checkOutUntil) {
    }

    /** @param changed price-relevant conditions (cancellation, board) differ from the search */
    public record Prebook(String prebookId, BigDecimal netPrice, String currency,
                          @Nullable BigDecimal suggestedSellingPrice, boolean changed, boolean refundable,
                          @Nullable LocalDateTime freeCancellationUntil) {
    }

    /** Person the booking is made for, passed through and not stored after booking. */
    public record Holder(String firstName, String lastName, String email, String phone) {
    }

    public record ProviderBooking(String bookingId, @Nullable String hotelConfirmationCode, String status) {
    }
}
