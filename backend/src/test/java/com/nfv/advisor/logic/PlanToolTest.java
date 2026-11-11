package com.nfv.advisor.logic;

import com.nfv.advisor.config.AdvisorProperties;
import com.nfv.advisor.entities.AdvisorProposal;
import com.nfv.advisor.entities.PlanStepKind;
import com.nfv.advisor.entities.ProposalKind;
import com.nfv.advisor.entities.ProposalStay;
import com.nfv.advisor.logic.StayOptions.BookedStay;
import com.nfv.assistant.logic.ToolInputException;
import com.nfv.network.logic.GeoPoint;
import com.nfv.recommendations.logic.Drives;
import com.nfv.recommendations.logic.OptionKind;
import com.nfv.recommendations.logic.SuggestedOption;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** See Docs/incident-plans.md#building-blocks-and-tools. */
class PlanToolTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneOffset NZ = ZoneOffset.ofHours(12);
    private static final OffsetDateTime DEPARTURE = OffsetDateTime.of(2027, 7, 12, 8, 0, 0, 0, NZ);
    private static final OffsetDateTime PLANNED_ARRIVAL = DEPARTURE.plusHours(8);
    private static final OffsetDateTime AT_AREA = DEPARTURE.plusHours(11);
    private static final RunContext.Target TARGET = new RunContext.Target(7, 8, "Wānaka", -44.70, 169.13,
            PLANNED_ARRIVAL, DEPARTURE.plusDays(3), 2);
    private static final BookedStay BOOKED = new BookedStay(30, -44.70, 169.13, LocalDate.of(2027, 7, 12),
            LocalDate.of(2027, 7, 14), 2, 1, new BigDecimal("300.00"), "NZD", "lp9", LocalTime.of(22, 0),
            "Lakeview Wānaka");

    private final Drives drives = mock(Drives.class);
    private final StayOptions stayOptions = mock(StayOptions.class);
    private final PlanTool tool = new PlanTool(drives, stayOptions, properties(true));

    @Test
    void nightOnTheWayWithCheckedWayOnKeepsTheBookingAndTellsIt() {
        RunContext run = run(blockedRoute());
        when(stayOptions.bookedStay(1, 7, 8)).thenReturn(Optional.of(BOOKED));
        String area = run.registerArea(new RunContext.Area("Geraldine", -44.09, 171.24, AT_AREA, null));
        String night = run.registerStay(ProposalDraft.stopover(run.keepRoute(), AT_AREA, stay("Geraldine Motel",
                LocalDate.of(2027, 7, 12), "128.00", null)), area, false);
        OffsetDateTime leave = OffsetDateTime.of(2027, 7, 13, 9, 0, 0, 0, NZ);
        when(drives.between(eq(new GeoPoint(-44.09, 171.24)), eq(new GeoPoint(-44.70, 169.13)), eq(leave)))
                .thenReturn(new Drives.Drive(option(leave, leave.plusHours(3).plusMinutes(30)), null, null));

        JsonNode answer = tool.call(run, arguments(null, night, null));

        assertThat(answer.path("plan_id").asString()).isEqualTo("plan_1");
        assertThat(answer.path("problems")).isEmpty();
        assertThat(answer.path("steps")).extracting(step -> step.path("step").asString())
                .containsExactly("DRIVE", "OVERNIGHT", "CONTINUE", "KEEP_BOOKING");
        assertThat(answer.path("steps").get(3).path("tell_accommodation_arrival_on").asString())
                .isEqualTo("2027-07-13");
        assertThat(answer.path("arrival_at").asString()).isEqualTo(leave.plusHours(3).plusMinutes(30).toString());
        assertThat(answer.path("deadline").asString()).isEqualTo(OffsetDateTime.of(2027, 7, 12, 22, 0, 0, 0, NZ)
                .toString());
        assertThat(answer.path("meets_deadline").asBoolean()).isFalse();
        assertThat(answer.path("extra_nights").asInt()).isEqualTo(1);
        assertThat(answer.path("extra_cost").decimalValue()).isEqualByComparingTo("128.00");
        assertThat(answer.path("reaches_planned_target").asBoolean()).isTrue();
        assertThat(answer.path("later_stops_move").asBoolean()).isFalse();

        AdvisorProposal proposal = run.plan("plan_1").orElseThrow().toProposal(0, "Safe");
        assertThat(proposal.getKind()).isEqualTo(ProposalKind.PLAN);
        assertThat(proposal.getSteps()).extracting(s -> s.getKind()).containsExactly(PlanStepKind.DRIVE,
                PlanStepKind.OVERNIGHT, PlanStepKind.CONTINUE, PlanStepKind.KEEP_BOOKING);
        assertThat(proposal.getSteps().get(3).getBookingId()).isEqualTo(30);
        assertThat(proposal.getDelay()).isEqualTo(Duration.between(PLANNED_ARRIVAL, leave.plusHours(3).plusMinutes(30)));
    }

    @Test
    void blockedWayOnIsNoPlan() {
        RunContext run = run(blockedRoute());
        when(stayOptions.bookedStay(1, 7, 8)).thenReturn(Optional.of(BOOKED));
        String area = run.registerArea(new RunContext.Area("Geraldine", -44.09, 171.24, AT_AREA, null));
        String night = run.registerStay(ProposalDraft.stopover(run.keepRoute(), AT_AREA, stay("Geraldine Motel",
                LocalDate.of(2027, 7, 12), "128.00", null)), area, false);
        when(drives.between(any(), any(), any()))
                .thenReturn(new Drives.Drive(null, null, Drives.DriveProblem.BLOCKED));

        JsonNode answer = tool.call(run, arguments(null, night, null));

        assertThat(answer.path("plan_id").isNull()).isTrue();
        assertThat(answer.path("problems")).extracting(JsonNode::asString).contains("CONTINUATION_BLOCKED");
        assertThat(run.plan("plan_1")).isEmpty();
    }

    @Test
    void lateButWithinTheCheckInIsKeptAndMeetsTheDeadline() {
        RunContext run = run(route(PLANNED_ARRIVAL.plusHours(4)));
        when(stayOptions.bookedStay(1, 7, 8)).thenReturn(Optional.of(BOOKED));

        JsonNode answer = tool.call(run, arguments(null, null, PlanTool.KEEP_BOOKING));

        assertThat(answer.path("plan_id").asString()).isEqualTo("plan_1");
        assertThat(answer.path("meets_deadline").asBoolean()).isTrue();
        assertThat(answer.path("steps")).extracting(step -> step.path("step").asString()).containsExactly("DRIVE");
        assertThat(answer.path("extra_cost").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void arrivalOnTheDayOfCheckOutLosesTheBooking() {
        RunContext run = run(route(OffsetDateTime.of(2027, 7, 14, 10, 0, 0, 0, NZ)));
        when(stayOptions.bookedStay(1, 7, 8)).thenReturn(Optional.of(BOOKED));

        JsonNode answer = tool.call(run, arguments(null, null, null));

        assertThat(answer.path("plan_id").isNull()).isTrue();
        assertThat(answer.path("problems")).extracting(JsonNode::asString).containsExactly("BOOKED_STAY_LOST");
    }

    @Test
    void otherTargetMovesTheTargetStop() {
        RunContext run = run(blockedRoute());
        when(stayOptions.bookedStay(1, 7, 8)).thenReturn(Optional.of(BOOKED));
        OffsetDateTime there = DEPARTURE.plusHours(6);
        String place = run.registerPlace(new RunContext.Place("Twizel", -44.26, 170.10,
                ProposalDraft.of(option(DEPARTURE, there), null)));
        String hotel = run.registerStay(ProposalDraft.targetStay(run.keepRoute(), stay("Twizel Lodge",
                LocalDate.of(2027, 7, 12), "210.00", 30L)), place, true);

        assertThat(run.option(hotel)).isEmpty();
        JsonNode answer = tool.call(run, arguments(null, null, hotel));

        assertThat(answer.path("plan_id").asString()).isEqualTo("plan_1");
        assertThat(answer.path("steps")).extracting(step -> step.path("step").asString())
                .containsExactly("DRIVE", "NEW_TARGET");
        assertThat(answer.path("reaches_planned_target").asBoolean()).isFalse();
        assertThat(answer.path("new_target").asString()).isEqualTo("Twizel");
        assertThat(answer.path("arrival_at").asString()).isEqualTo(there.toString());
        assertThat(answer.path("meets_deadline").isNull()).isTrue();
    }

    @Test
    void partsThatDoNotFitGoBackToTheModel() {
        RunContext run = run(blockedRoute());
        when(stayOptions.bookedStay(1, 7, 8)).thenReturn(Optional.of(BOOKED));
        String place = run.registerPlace(new RunContext.Place("Twizel", -44.26, 170.10,
                ProposalDraft.of(option(DEPARTURE, DEPARTURE.plusHours(6)), null)));
        String hotel = run.registerStay(ProposalDraft.targetStay(run.keepRoute(), stay("Twizel Lodge",
                LocalDate.of(2027, 7, 12), "210.00", 30L)), place, true);
        String target = run.registerStay(ProposalDraft.targetStay(run.keepRoute(), stay("Wānaka Inn",
                LocalDate.of(2027, 7, 12), "150.00", 30L)), RunContext.TARGET, false);

        assertThatThrownBy(() -> tool.call(run, arguments("route_99", null, null)))
                .isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> tool.call(run, arguments(RunContext.KEEP_ROUTE, null, hotel)))
                .isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> tool.call(run, arguments(null, target, null)))
                .isInstanceOf(ToolInputException.class);
        assertThatThrownBy(() -> tool.call(run, arguments(null, null, "stay_99")))
                .isInstanceOf(ToolInputException.class);
    }

    @Test
    void checkInUntilAfterMidnightMeansTheNightAfter() {
        RunContext run = run(route(PLANNED_ARRIVAL));
        BookedStay late = new BookedStay(30, null, null, LocalDate.of(2027, 7, 12), LocalDate.of(2027, 7, 14), 2, 1,
                null, "NZD", null, LocalTime.of(1, 0), "Late Inn");

        assertThat(PlanTool.deadline(run, late, NZ)).isEqualTo(OffsetDateTime.of(2027, 7, 13, 1, 0, 0, 0, NZ));
        assertThat(PlanTool.deadline(run, null, NZ)).isEqualTo(PLANNED_ARRIVAL);
    }

    @Test
    void appliesOnlyIfPlansAndStaysAreEnabled() {
        assertThat(tool.appliesTo(run(blockedRoute()))).isTrue();
        assertThat(new PlanTool(drives, stayOptions, properties(false)).appliesTo(run(blockedRoute()))).isFalse();
        assertThat(tool.parameters().path("required")).hasSize(3);
    }

    private static ProposalStay stay(String name, LocalDate checkIn, String price, Long replaces) {
        return new ProposalStay("lp-" + name, "offer-" + name, name, -44.09, 171.24, "Geraldine", 300, checkIn,
                checkIn.plusDays(1), 2, 1, new BigDecimal(price), "NZD", true, null, replaces);
    }

    private static SuggestedOption option(OffsetDateTime departure, OffsetDateTime arrival) {
        return new SuggestedOption(1, OptionKind.ALTERNATIVES, Set.of(), "SH 8", departure, arrival, 250_000,
                Duration.between(departure, arrival), Duration.ZERO, Duration.ZERO, null, List.of(),
                List.of(new SuggestedOption.Point(-44.3, 170.5, "SH 8", null)), List.of(), List.of());
    }

    private static ObjectNode arguments(String drive, String overnight, String target) {
        ObjectNode arguments = JSON.createObjectNode();
        arguments.put("drive", drive);
        arguments.put("overnight", overnight);
        arguments.put("target", target);
        return arguments;
    }

    private static ProposalDraft blockedRoute() {
        return route(null);
    }

    private static ProposalDraft route(OffsetDateTime arrival) {
        return new ProposalDraft(ProposalKind.KEEP_ROUTE, null, DEPARTURE, arrival, null, null, null, null, null,
                List.of(), List.of(), List.of(), null);
    }

    private static AdvisorProperties properties(boolean plans) {
        return new AdvisorProperties(16, 3, Duration.ofMinutes(15), false, true, plans, true);
    }

    private static RunContext run(ProposalDraft keepRoute) {
        return new RunContext(1, 2, false, PLANNED_ARRIVAL, true, keepRoute, TARGET)
                .plannedArrival(PLANNED_ARRIVAL, null);
    }
}
