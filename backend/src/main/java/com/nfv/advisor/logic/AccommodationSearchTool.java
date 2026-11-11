package com.nfv.advisor.logic;

import com.nfv.advisor.config.AdvisorProperties;
import com.nfv.advisor.entities.ProposalStay;
import com.nfv.advisor.logic.StayOptions.BookedStay;
import com.nfv.advisor.logic.StayOptions.InvalidStaySearchException;
import com.nfv.advisor.logic.StayOptions.StayOffer;
import com.nfv.advisor.logic.StayOptions.StaySearch;
import com.nfv.advisor.logic.StayOptions.StaySearchUnavailableException;
import com.nfv.assistant.logic.ToolInputException;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * {@code search_accommodations}: hotels with a bookable offer around an area of {@code find_overnight_areas} or the
 * target stop, see Docs/advisor.md#search_accommodations. Every offer becomes a {@code stay_} option the model can
 * propose. Searches LiteAPI, never books and never calls the model.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AccommodationSearchTool implements AdvisorTool {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final int DEFAULT_RADIUS_METERS = 5_000;
    static final int DEFAULT_RESULTS = 3;
    static final int MAX_RESULTS = 5;
    /** Without anything known about the travellers. */
    static final int DEFAULT_GUESTS = 2;
    static final String DEFAULT_CURRENCY = "EUR";
    private static final String SCHEMA = """
            {"type":"object","properties":{
            "area_id":{"type":["string","null"],
            "description":"An area_ from find_overnight_areas, a place_ from find_other_targets, null for the target stop of the route"},
            "radius_km":{"type":["number","null"],"description":"Around the area or stop, 0.5 to 50, null for 5"},
            "check_in":{"type":["string","null"],
            "description":"ISO date, null for the day of arrival at the area, or the booked or planned stay at the target stop"},
            "check_out":{"type":["string","null"],"description":"ISO date, null for the day after check_in, or the \
            booked or planned end of the stay at the target stop"},
            "max_price":{"type":["number","null"],"description":"Total for the stay in the currency of the answer, \
            null for no limit, at the target stop for the price of the booked stay"},
            "max_results":{"type":["integer","null"],"description":"Offers wanted, 1 to 5, null for 3"}},
            "required":["area_id","radius_km","check_in","check_out","max_price","max_results"],
            "additionalProperties":false}""";

    private final StayOptions stayOptions;
    private final AdvisorProperties properties;

    @Override
    public String name() {
        return "search_accommodations";
    }

    @Override
    public String description() {
        return "Hotels with a bookable offer for a night along the way (around an area_ of find_overnight_areas), at "
                + "another target (around a place_ of find_other_targets) or at the target stop (area_id null, e.g. "
                + "when the booked accommodation cannot be reached in time or is lost). Each offer is a stay_ option for "
                + "compose_plan; those along the way or at the target can also be proposed alone. Prices are totals "
                + "for the stay. Every call costs, search only where a stay makes sense.";
    }

    @Override
    public JsonNode parameters() {
        return JSON.readTree(SCHEMA);
    }

    @Override
    public boolean appliesTo(RunContext run) {
        return properties.stopoverEnabled() && run.overnightRelevant() && stayOptions.available();
    }

    @Override
    public JsonNode call(RunContext run, JsonNode arguments) {
        String areaId = text(arguments, "area_id");
        Where where = areaId == null ? target(run) : areaId.startsWith("place_") ? place(run, areaId)
                : area(run, areaId);
        ObjectNode answer = JSON.createObjectNode();
        answer.put("area_id", areaId);
        answer.put("place", where.place());
        ArrayNode offers = answer.putArray("offers");
        if (where.latitude() == null || where.longitude() == null) {
            answer.put("problem", "NO_LOCATION");
            return answer;
        }
        BookedStay booked = where.booked().orElse(null);
        LocalDate checkIn = date(arguments, "check_in", where.checkIn());
        if (checkIn == null) {
            throw new ToolInputException("check_in is needed, the target stop has no arrival");
        }
        LocalDate checkOut = date(arguments, "check_out",
                where.checkOut() != null && where.checkOut().isAfter(checkIn) ? where.checkOut() : checkIn.plusDays(1));
        int guests = booked != null && booked.guests() != null ? booked.guests()
                : run.target() != null && run.target().travelers() != null ? run.target().travelers() : DEFAULT_GUESTS;
        int rooms = booked != null ? Math.max(1, Math.min(booked.rooms(), guests)) : rooms(guests);
        // one currency for all hotels of a plan: one PayPal order
        String currency = booked != null ? booked.currency() : run.target() == null ? DEFAULT_CURRENCY
                : run.bookedStay(() -> stayOptions.bookedStay(run.appUserId(), run.target().tripId(),
                run.target().stopId())).map(BookedStay::currency).orElse(DEFAULT_CURRENCY);
        BigDecimal maxPrice = maxPrice(arguments, booked != null && areaId == null ? booked.maxPrice() : null);
        int results = results(arguments);
        answer.put("check_in", checkIn.toString());
        answer.put("check_out", checkOut.toString());
        answer.put("nights", ChronoUnit.DAYS.between(checkIn, checkOut));
        answer.put("guests", guests);
        answer.put("rooms", rooms);
        answer.put("currency", currency);
        answer.put("max_price", maxPrice);

        List<StayOffer> found;
        try {
            found = stayOptions.search(run.appUserId(), new StaySearch(where.latitude(), where.longitude(),
                    radius(arguments), checkIn, checkOut, guests, rooms, maxPrice, currency,
                    areaId == null && booked != null ? booked.hotelId() : null, results));
        } catch (InvalidStaySearchException e) {
            throw new ToolInputException(e.getMessage());
        } catch (StaySearchUnavailableException e) {
            log.info("Advisor: accommodation search unavailable: {}", e.getMessage());
            answer.put("problem", e.rateLimited() ? "RATE_LIMITED" : "SEARCH_UNAVAILABLE");
            return answer;
        }
        answer.put("problem", found.isEmpty() ? "NO_OFFERS" : null);
        Long replaces = areaId == null && booked != null ? Long.valueOf(booked.bookingId())
                : where.planOnly() ? bookedAtTarget(run).map(BookedStay::bookingId).orElse(null) : null;
        for (StayOffer offer : found) {
            ProposalStay stay = new ProposalStay(offer.hotelId(), offer.offerId(), offer.name(), offer.latitude(),
                    offer.longitude(), where.place(), offer.distanceMeters(), checkIn, checkOut, guests, rooms,
                    offer.price(), offer.currency(), offer.refundable(), offer.freeCancellationUntil(), replaces);
            ProposalDraft draft = where.arrivalAt() == null || where.planOnly()
                    ? ProposalDraft.targetStay(where.route(), stay)
                    : ProposalDraft.stopover(where.route(), where.arrivalAt(), stay);
            ObjectNode option = draft.describe(JSON, run.registerStay(draft, where.origin(), where.planOnly()));
            option.put("only_in_plan", where.planOnly());
            ObjectNode hotel = (ObjectNode) option.path("stay");
            hotel.put("stars", offer.stars());
            hotel.put("rating", offer.rating());
            hotel.put("board", offer.boardName());
            offers.add(option);
        }
        return answer;
    }

    /**
     * Where to search and what a stay there starts from.
     *
     * @param arrivalAt at the area, null at the target stop
     * @param route     the way the stay lies on
     */
    private record Where(String place, @Nullable Double latitude, @Nullable Double longitude,
                         @Nullable OffsetDateTime arrivalAt, ProposalDraft route, @Nullable LocalDate checkIn,
                         @Nullable LocalDate checkOut, Optional<BookedStay> booked, String origin, boolean planOnly) {
    }

    /** Another target: the way there is the drive of the place, a stay there only works in a plan. */
    private Where place(RunContext run, String placeId) {
        RunContext.Place place = run.place(placeId)
                .orElseThrow(() -> new ToolInputException("unknown area_id " + placeId));
        OffsetDateTime arrival = place.drive().arrivalAt();
        LocalDate day = arrival == null ? null : arrival.atZoneSameInstant(run.targetOffset()).toLocalDate();
        LocalDate checkOut = bookedAtTarget(run).map(BookedStay::checkOut)
                .filter(out -> day != null && out.isAfter(day)).orElse(day == null ? null : day.plusDays(1));
        return new Where(place.name(), place.latitude(), place.longitude(), arrival, place.drive(), day, checkOut,
                Optional.empty(), placeId, true);
    }

    private Optional<BookedStay> bookedAtTarget(RunContext run) {
        RunContext.Target target = run.target();
        return target == null ? Optional.empty()
                : run.bookedStay(() -> stayOptions.bookedStay(run.appUserId(), target.tripId(), target.stopId()));
    }

    private static Where area(RunContext run, String areaId) {
        RunContext.Area area = run.area(areaId)
                .orElseThrow(() -> new ToolInputException("unknown area_id " + areaId));
        ProposalDraft route = area.routeOptionId() == null ? run.keepRoute()
                : run.option(area.routeOptionId()).orElseThrow();
        LocalDate day = area.arrivalAt().toLocalDate();
        return new Where(area.name(), area.latitude(), area.longitude(), area.arrivalAt(), route, day,
                day.plusDays(1), Optional.empty(), areaId, false);
    }

    private Where target(RunContext run) {
        RunContext.Target target = run.target();
        if (target == null) {
            throw new ToolInputException("the route has no target stop");
        }
        Optional<BookedStay> booked = run.bookedStay(() -> stayOptions.bookedStay(run.appUserId(), target.tripId(),
                target.stopId()));
        Double latitude = booked.map(BookedStay::latitude).orElse(target.latitude());
        Double longitude = booked.map(BookedStay::longitude).orElse(target.longitude());
        if (latitude == null || longitude == null) {
            latitude = target.latitude();
            longitude = target.longitude();
        }
        LocalDate checkIn = booked.map(BookedStay::checkIn)
                .orElse(target.arrivalAt() == null ? null : target.arrivalAt().toLocalDate());
        LocalDate checkOut = booked.map(BookedStay::checkOut)
                .orElse(target.departureAt() == null ? null : target.departureAt().toLocalDate());
        return new Where(target.name(), latitude, longitude, null, run.keepRoute(), checkIn, checkOut, booked,
                RunContext.TARGET, false);
    }

    /** One room for two, as in a double room: LiteAPI prices per occupancy, the user can search again when booking. */
    static int rooms(int guests) {
        return Math.max(1, (guests + 1) / 2);
    }

    private static @Nullable String text(JsonNode arguments, String field) {
        JsonNode value = arguments.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    private static @Nullable LocalDate date(JsonNode arguments, String field, @Nullable LocalDate fallback) {
        String value = text(arguments, field);
        if (value == null) {
            return fallback;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new ToolInputException(field + " must be an ISO date like 2027-07-12");
        }
    }

    private static int radius(JsonNode arguments) {
        JsonNode value = arguments.path("radius_km");
        return value.isNumber() ? (int) Math.round(value.asDouble() * 1000) : DEFAULT_RADIUS_METERS;
    }

    private static @Nullable BigDecimal maxPrice(JsonNode arguments, @Nullable BigDecimal fallback) {
        JsonNode value = arguments.path("max_price");
        return value.isNumber() ? value.decimalValue().setScale(2, RoundingMode.DOWN) : fallback;
    }

    private static int results(JsonNode arguments) {
        JsonNode value = arguments.path("max_results");
        if (!value.isIntegralNumber()) {
            return DEFAULT_RESULTS;
        }
        if (value.asInt() < 1 || value.asInt() > MAX_RESULTS) {
            throw new ToolInputException("max_results must be between 1 and " + MAX_RESULTS);
        }
        return value.asInt();
    }

}
