package com.nfv.advisor.logic;

import com.nfv.advisor.config.AdvisorProperties;
import com.nfv.advisor.entities.AdvisorProposal;
import com.nfv.advisor.entities.ProposalKind;
import com.nfv.advisor.entities.ProposalPoint;
import com.nfv.advisor.logic.StayOptions.BookedStay;
import com.nfv.advisor.logic.StayOptions.InvalidStaySearchException;
import com.nfv.advisor.logic.StayOptions.StayOffer;
import com.nfv.advisor.logic.StayOptions.StaySearch;
import com.nfv.advisor.logic.StayOptions.StaySearchUnavailableException;
import com.nfv.assistant.logic.ToolInputException;
import com.nfv.network.logic.RoutedSegment;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** See Docs/advisor.md#search_accommodations. */
class AccommodationSearchToolTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.of(2027, 7, 12, 8, 0, 0, 0, ZoneOffset.ofHours(12));
    private static final OffsetDateTime AT_AREA = DEPARTURE.plusHours(11);
    private static final List<RoutedSegment> PATH = List.of(new RoutedSegment(1, 0, 1, 1000, Duration.ofMinutes(1)));
    private static final StayOffer OFFER = new StayOffer("lp1", "offer-1", "Lakeside Lodge", -38.69, 176.08, 800, 3.0,
            8.4, "Breakfast", new BigDecimal("189.00"), "EUR", true, LocalDateTime.of(2027, 7, 11, 18, 0));
    private static final RunContext.Target TARGET = new RunContext.Target(7, 8, "Thorndon", -41.2755, 174.779,
            DEPARTURE.plusHours(8), DEPARTURE.plusDays(2), 3);

    private final StayOptions stayOptions = mock(StayOptions.class);
    private final AccommodationSearchTool tool = new AccommodationSearchTool(stayOptions, properties(true));
    private final RunContext run = run(true, TARGET);

    @Test
    void offersAroundAnAreaBecomeStopoversOnThePlannedRoute() {
        String area = run.registerArea(new RunContext.Area("Taupō", -38.68, 176.07, AT_AREA, null));
        when(stayOptions.search(eq(1L), any())).thenReturn(List.of(OFFER));

        JsonNode answer = tool.call(run, arguments(area).put("max_price", 250.555));

        StaySearch search = searched();
        assertThat(search.latitude()).isEqualTo(-38.68);
        assertThat(search.radiusMeters()).isEqualTo(5_000);
        assertThat(search.checkIn()).isEqualTo(LocalDate.of(2027, 7, 12));
        assertThat(search.checkOut()).isEqualTo(LocalDate.of(2027, 7, 13));
        assertThat(search.guests()).isEqualTo(3);
        assertThat(search.rooms()).isEqualTo(2);
        assertThat(search.maxPrice()).isEqualByComparingTo("250.55");
        assertThat(search.currency()).isEqualTo("EUR");
        assertThat(search.excludeHotelId()).isNull();
        assertThat(search.maxResults()).isEqualTo(3);
        assertThat(answer.path("problem").isNull()).isTrue();
        assertThat(answer.path("nights").asInt()).isEqualTo(1);
        JsonNode option = answer.path("offers").get(0);
        assertThat(option.path("option_id").asString()).isEqualTo("stay_1");
        assertThat(option.path("kind").asString()).isEqualTo("STOPOVER");
        assertThat(option.path("arrival_at").asString()).isEqualTo(AT_AREA.toString());
        assertThat(option.path("stay").path("hotel").asString()).isEqualTo("Lakeside Lodge");
        assertThat(option.path("stay").path("price").decimalValue()).isEqualByComparingTo("189.00");
        assertThat(option.path("stay").path("stars").asDouble()).isEqualTo(3.0);
        assertThat(option.path("stay").path("replaces_booked_stay").asBoolean()).isFalse();
        verify(stayOptions, never()).bookedStay(anyLong(), anyLong(), anyLong());
    }

    @Test
    void stayCanBeProposedWithTheOfferFromTheBackend() {
        String area = run.registerArea(new RunContext.Area("Taupō", -38.68, 176.07, AT_AREA, null));
        when(stayOptions.search(eq(1L), any())).thenReturn(List.of(OFFER));

        tool.call(run, arguments(area));

        AdvisorProposal proposal = run.option("stay_1").orElseThrow().toProposal(0, "Sleep there.");
        assertThat(proposal.getKind()).isEqualTo(ProposalKind.STOPOVER);
        assertThat(proposal.getDepartureAt()).isEqualTo(DEPARTURE);
        assertThat(proposal.getStay()).isNotNull();
        assertThat(proposal.getStay().offerId()).isEqualTo("offer-1");
        assertThat(proposal.getStay().place()).isEqualTo("Taupō");
        assertThat(proposal.getStay().distanceMeters()).isEqualTo(800);
        assertThat(proposal.getStay().replacesBookingId()).isNull();
    }

    @Test
    void areaOnARouteOptionKeepsItsWay() {
        ProposalPoint point = new ProposalPoint(-39.0, 175.9, "SH 1", null);
        String route = run.register(new ProposalDraft(ProposalKind.REROUTE, "SH 1", DEPARTURE, DEPARTURE.plusHours(9),
                9000.0, Duration.ofHours(9), Duration.ofHours(1), 0.6, false, List.of(point), List.of(), PATH, null));
        String area = run.registerArea(new RunContext.Area("Taupō", -38.68, 176.07, AT_AREA, route));
        when(stayOptions.search(eq(1L), any())).thenReturn(List.of(OFFER));

        JsonNode answer = tool.call(run, arguments(area));

        ProposalDraft stay = run.option(answer.path("offers").get(0).path("option_id").asString()).orElseThrow();
        assertThat(stay.kind()).isEqualTo(ProposalKind.STOPOVER);
        assertThat(stay.via()).isEqualTo("SH 1");
        assertThat(stay.points()).containsExactly(point);
    }

    @Test
    void targetStopStartsFromTheBookedStay() {
        when(stayOptions.bookedStay(1, 7, 8)).thenReturn(Optional.of(new BookedStay(42, -41.28, 174.78,
                LocalDate.of(2027, 7, 12), LocalDate.of(2027, 7, 14), 4, 1, new BigDecimal("400.00"), "NZD", "lp9")));
        when(stayOptions.search(eq(1L), any())).thenReturn(List.of(OFFER));

        JsonNode answer = tool.call(run, arguments(null).put("radius_km", 2.5).put("max_results", 5));

        StaySearch search = searched();
        assertThat(search.latitude()).isEqualTo(-41.28);
        assertThat(search.radiusMeters()).isEqualTo(2_500);
        assertThat(search.checkIn()).isEqualTo(LocalDate.of(2027, 7, 12));
        assertThat(search.checkOut()).isEqualTo(LocalDate.of(2027, 7, 14));
        assertThat(search.guests()).isEqualTo(4);
        assertThat(search.rooms()).isEqualTo(1);
        assertThat(search.maxPrice()).isEqualByComparingTo("400.00");
        assertThat(search.currency()).isEqualTo("NZD");
        assertThat(search.excludeHotelId()).isEqualTo("lp9");
        assertThat(search.maxResults()).isEqualTo(5);
        JsonNode option = answer.path("offers").get(0);
        assertThat(option.path("kind").asString()).isEqualTo("TARGET_STAY");
        assertThat(option.path("stay").path("replaces_booked_stay").asBoolean()).isTrue();
        assertThat(run.option(option.path("option_id").asString()).orElseThrow().stay().replacesBookingId())
                .isEqualTo(42);
    }

    @Test
    void targetStopWithoutBookingUsesItsTimesAndTheTravelers() {
        when(stayOptions.bookedStay(1, 7, 8)).thenReturn(Optional.empty());
        when(stayOptions.search(eq(1L), any())).thenReturn(List.of());

        JsonNode answer = tool.call(run, arguments(null));

        StaySearch search = searched();
        assertThat(search.latitude()).isEqualTo(-41.2755);
        assertThat(search.checkIn()).isEqualTo(LocalDate.of(2027, 7, 12));
        assertThat(search.checkOut()).isEqualTo(LocalDate.of(2027, 7, 14));
        assertThat(search.guests()).isEqualTo(3);
        assertThat(search.maxPrice()).isNull();
        assertThat(answer.path("problem").asString()).isEqualTo("NO_OFFERS");
    }

    @Test
    void targetStopWithoutCoordinatesIsNoLocation() {
        RunContext withoutPlace = run(true, new RunContext.Target(7, 8, "Somewhere", null, null, null, null, null));
        when(stayOptions.bookedStay(1, 7, 8)).thenReturn(Optional.empty());

        JsonNode answer = tool.call(withoutPlace, arguments(null));

        assertThat(answer.path("problem").asString()).isEqualTo("NO_LOCATION");
        verify(stayOptions, never()).search(anyLong(), any());
    }

    @Test
    void searchThatIsUsedUpGoesToTheModelAsProblem() {
        String area = run.registerArea(new RunContext.Area("Taupō", -38.68, 176.07, AT_AREA, null));
        when(stayOptions.search(eq(1L), any())).thenThrow(new StaySearchUnavailableException("too many", true));

        JsonNode answer = tool.call(run, arguments(area));

        assertThat(answer.path("problem").asString()).isEqualTo("RATE_LIMITED");
        assertThat(answer.path("offers")).isEmpty();
        assertThat(run.option("stay_1")).isEmpty();
    }

    @Test
    void wrongInputGoesBackToTheModel() {
        String area = run.registerArea(new RunContext.Area("Taupō", -38.68, 176.07, AT_AREA, null));
        when(stayOptions.search(eq(1L), any())).thenThrow(new InvalidStaySearchException("radius must be 100 m to 50 km"));
        RunContext withoutTimes = run(true, new RunContext.Target(7, 8, "Thorndon", -41.2755, 174.779, null, null, null));
        when(stayOptions.bookedStay(1, 7, 8)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> tool.call(run, arguments("area_99"))).isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> tool.call(run, arguments(area).put("check_in", "tomorrow")))
                .isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> tool.call(run, arguments(area).put("max_results", 9)))
                .isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> tool.call(run, arguments(area).put("radius_km", 90)))
                .isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> tool.call(withoutTimes, arguments(null))).isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> tool.call(run(true, null), arguments(null))).isInstanceOf(ToolInputException.class);
    }

    @Test
    void appliesOnlyIfEnabledRelevantAndLiteApiConfigured() {
        when(stayOptions.available()).thenReturn(true);
        assertThat(tool.appliesTo(run(true, TARGET))).isTrue();
        assertThat(tool.appliesTo(run(false, TARGET))).isFalse();
        assertThat(new AccommodationSearchTool(stayOptions, properties(false)).appliesTo(run)).isFalse();
        when(stayOptions.available()).thenReturn(false);
        assertThat(tool.appliesTo(run)).isFalse();
    }

    @Test
    void parametersAreStrict() {
        JsonNode parameters = tool.parameters();

        assertThat(parameters.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(parameters.path("required")).hasSize(6);
    }

    @Test
    void roomsForTwoEach() {
        assertThat(AccommodationSearchTool.rooms(1)).isEqualTo(1);
        assertThat(AccommodationSearchTool.rooms(2)).isEqualTo(1);
        assertThat(AccommodationSearchTool.rooms(3)).isEqualTo(2);
        assertThat(AccommodationSearchTool.rooms(5)).isEqualTo(3);
    }

    private StaySearch searched() {
        ArgumentCaptor<StaySearch> search = ArgumentCaptor.forClass(StaySearch.class);
        verify(stayOptions).search(eq(1L), search.capture());
        return search.getValue();
    }

    private static ObjectNode arguments(String areaId) {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.put("area_id", areaId);
        arguments.putNull("radius_km");
        arguments.putNull("check_in");
        arguments.putNull("check_out");
        arguments.putNull("max_price");
        arguments.putNull("max_results");
        return arguments;
    }

    private static AdvisorProperties properties(boolean enabled) {
        return new AdvisorProperties(8, 3, Duration.ofMinutes(15), false, enabled);
    }

    private static RunContext run(boolean relevant, RunContext.Target target) {
        ProposalDraft keepRoute = new ProposalDraft(ProposalKind.KEEP_ROUTE, null, DEPARTURE, null, null, null, null,
                null, null, List.of(), List.of(), List.of(), null);
        return new RunContext(1, 2, false, null, relevant, keepRoute, target);
    }
}
