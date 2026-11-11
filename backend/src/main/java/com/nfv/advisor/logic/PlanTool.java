package com.nfv.advisor.logic;

import com.nfv.advisor.config.AdvisorProperties;
import com.nfv.advisor.entities.PlanResult;
import com.nfv.advisor.entities.PlanStepKind;
import com.nfv.advisor.entities.ProposalConflict;
import com.nfv.advisor.entities.ProposalKind;
import com.nfv.advisor.entities.ProposalStay;
import com.nfv.advisor.logic.StayOptions.BookedStay;
import com.nfv.assistant.logic.ToolInputException;
import com.nfv.network.logic.GeoPoint;
import com.nfv.recommendations.logic.Drives;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code compose_plan}: builds a plan of several steps from options of the run and checks it, see
 * Docs/incident-plans.md#building-blocks-and-tools. The backend computes the timeline, the deadline, the costs and
 * the way on after a night; a plan with a problem gets no id. Never books, never changes the trip.
 */
@Component
@RequiredArgsConstructor
class PlanTool implements AdvisorTool {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Departure after a night on the way, local, unless a closure ends later. */
    static final LocalTime CONTINUE_AT = ProposalDecisions.STOPOVER_DEPARTURE;
    /** A latest check-in before this time means the night after. */
    static final LocalTime NIGHT_ENDS = LocalTime.of(6, 0);
    static final String KEEP_BOOKING = "keep_booking";
    private static final String SCHEMA = """
            {"type":"object","properties":{
            "drive":{"type":["string","null"],"description":"keep_route, a route_ or departure_ option for the way \
            (to the night or to the target), null for keep_route; must be null with a stay at another target"},
            "overnight":{"type":["string","null"],"description":"A stay_ around an area_ of find_overnight_areas \
            along the drive, null without a night on the way"},
            "target":{"type":["string","null"],"description":"keep_booking or null: keep the booked accommodation \
            (informed if late); a stay_ at the target stop: replace it; a stay_ at a place_ of find_other_targets: \
            move the target there"}},
            "required":["drive","overnight","target"],"additionalProperties":false}""";

    private final Drives drives;
    private final StayOptions stayOptions;
    private final AdvisorProperties properties;

    @Override
    public String name() {
        return "compose_plan";
    }

    @Override
    public String description() {
        return "Combines options into a plan and checks it: the drive, optionally a night on the way (the way on the "
                + "next morning is routed and checked), and what happens at the target (keep and inform the booked "
                + "accommodation, replace it, or another target). Returns the timeline with arrival, deadline, extra "
                + "nights and costs. A plan without problems gets a plan_id for submit_proposals; with problems, "
                + "change the parts and try again.";
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
        String driveId = text(arguments, "drive");
        String overnightId = text(arguments, "overnight");
        String targetId = text(arguments, "target");
        RunContext.StayChoice targetStay = targetId == null || KEEP_BOOKING.equals(targetId) ? null
                : run.stay(targetId).orElseThrow(() -> new ToolInputException("unknown target " + targetId));
        RunContext.StayChoice overnight = overnightId == null ? null
                : run.stay(overnightId).orElseThrow(() -> new ToolInputException("unknown overnight " + overnightId));
        if (overnight != null && !overnight.origin().startsWith("area_")) {
            throw new ToolInputException("overnight must be a stay_ around an area_ of find_overnight_areas");
        }
        boolean newTarget = targetStay != null && targetStay.origin().startsWith("place_");
        if (newTarget && (driveId != null || overnight != null)) {
            throw new ToolInputException("with another target, drive and overnight must be null: the way there "
                    + "comes with the place_");
        }
        Composed composed = newTarget ? otherTarget(run, targetStay) : compose(run, driveId, overnight, targetStay);
        String id = composed.problems().isEmpty() ? run.registerPlan(composed.plan()) : null;
        return composed.plan().describe(JSON, id, composed.problems());
    }

    /** @param problems the plan does not work as it is, it gets no id */
    record Composed(PlanDraft plan, List<String> problems) {
    }

    /** The planned target, with or without a night on the way. */
    private Composed compose(RunContext run, @Nullable String driveId, @Nullable RunContext.StayChoice overnight,
                              @Nullable RunContext.StayChoice targetStay) {
        ProposalDraft drive = drive(run, driveId);
        RunContext.Target target = Objects.requireNonNull(run.target());
        ZoneOffset offset = run.targetOffset();
        List<PlanDraft.Step> steps = new ArrayList<>();
        List<ProposalConflict> conflicts = new ArrayList<>(drive.conflicts());
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<ProposalStay> paid = new ArrayList<>();
        int extraNights = 0;
        OffsetDateTime arrival;
        if (overnight == null) {
            steps.add(PlanDraft.Step.drive(drive, drive.arrivalAt()));
            arrival = drive.arrivalAt();
            if (arrival == null) {
                problems.add("TARGET_NOT_REACHED");
            }
        } else {
            RunContext.Area area = run.area(overnight.origin()).orElseThrow();
            String areaRoute = area.routeOptionId() == null ? RunContext.KEEP_ROUTE : area.routeOptionId();
            if (!areaRoute.equals(driveId == null ? RunContext.KEEP_ROUTE : driveId)) {
                throw new ToolInputException("the area of the overnight lies along " + areaRoute
                        + ", use it as drive");
            }
            ProposalStay stay = Objects.requireNonNull(overnight.draft().stay());
            steps.add(PlanDraft.Step.drive(drive, area.arrivalAt()));
            steps.add(PlanDraft.Step.stay(PlanStepKind.OVERNIGHT, stay, area.name()));
            paid.add(stay);
            extraNights += (int) ChronoUnit.DAYS.between(stay.checkIn(), stay.checkOut());
            if (!stay.checkIn().equals(area.arrivalAt().atZoneSameInstant(offset).toLocalDate())) {
                problems.add("STAY_DATES_MISMATCH");
            }
            arrival = null;
            if (target.latitude() == null || target.longitude() == null) {
                problems.add("NO_LOCATION");
            } else {
                OffsetDateTime leave = OffsetDateTime.of(stay.checkOut(), CONTINUE_AT, offset);
                Drives.Drive on = drives.between(new GeoPoint(stay.latitude(), stay.longitude()),
                        new GeoPoint(target.latitude(), target.longitude()), leave);
                if (on.option() == null) {
                    problems.add(on.problem() == Drives.DriveProblem.NO_ROUTE ? "NO_ROUTE" : "CONTINUATION_BLOCKED");
                } else {
                    ProposalDraft next = ProposalDraft.of(on.option(), null);
                    steps.add(PlanDraft.Step.continuation(next, on.waitedFor()));
                    conflicts.addAll(next.conflicts());
                    arrival = next.arrivalAt();
                }
            }
        }
        LocalDate arrivalDay = arrival == null ? null : arrival.atZoneSameInstant(offset).toLocalDate();
        Optional<BookedStay> booked = run.bookedStay(() -> stayOptions.bookedStay(run.appUserId(), target.tripId(),
                target.stopId()));
        OffsetDateTime deadline = deadline(run, booked.orElse(null), offset);
        Boolean meets = null;
        if (targetStay != null) {
            if (!RunContext.TARGET.equals(targetStay.origin())) {
                throw new ToolInputException("target must be keep_booking, a stay_ at the target stop or at a place_");
            }
            ProposalStay stay = Objects.requireNonNull(targetStay.draft().stay());
            steps.add(PlanDraft.Step.stay(PlanStepKind.REPLACE_STAY, stay, target.name()));
            paid.add(stay);
            if (arrivalDay != null && !stay.checkIn().equals(arrivalDay)) {
                problems.add("STAY_DATES_MISMATCH");
            }
            // the new hotel takes the guests when they come
            deadline = null;
        } else if (booked.isPresent() && arrival != null) {
            BookedStay stay = booked.get();
            if (stay.checkOut() != null && !arrivalDay.isBefore(stay.checkOut())) {
                problems.add("BOOKED_STAY_LOST");
            } else {
                boolean nightLost = stay.checkIn() != null && arrivalDay.isAfter(stay.checkIn());
                boolean late = deadline != null && arrival.isAfter(deadline);
                if (nightLost || late) {
                    steps.add(PlanDraft.Step.keepBooking(stay.bookingId(), nightLost ? arrivalDay : null, arrival));
                }
                if (late && !nightLost) {
                    warnings.add("LATE_FOR_CHECK_IN");
                }
            }
        }
        if (deadline != null) {
            meets = arrival != null && !arrival.isAfter(deadline);
        }
        Costs costs = costs(paid, problems);
        OffsetDateTime planned = run.plannedArrival();
        Duration delay = arrival == null || planned == null ? null : Duration.between(planned, arrival);
        boolean affects = arrival != null && target.departureAt() != null && arrival.isAfter(target.departureAt());
        PlanResult result = new PlanResult(deadline, true, extraNights, costs.total(), costs.currency(), affects, null);
        return new Composed(new PlanDraft(steps, drive.departureAt(), arrival, delay, meets, conflicts, result,
                warnings), problems);
    }

    /** Another target: the way there from the place, the target stop moves to the hotel. */
    private Composed otherTarget(RunContext run, RunContext.StayChoice choice) {
        RunContext.Place place = run.place(choice.origin()).orElseThrow();
        ProposalStay stay = Objects.requireNonNull(choice.draft().stay());
        ProposalDraft drive = place.drive();
        OffsetDateTime arrival = drive.arrivalAt();
        List<String> problems = new ArrayList<>();
        if (arrival != null && !stay.checkIn().equals(arrival.atZoneSameInstant(run.targetOffset()).toLocalDate())) {
            problems.add("STAY_DATES_MISMATCH");
        }
        Costs costs = costs(List.of(stay), problems);
        RunContext.Target target = run.target();
        boolean affects = target == null || target.departureAt() == null || arrival == null
                || arrival.isAfter(target.departureAt()) || run.nextStop() != null;
        List<PlanDraft.Step> steps = List.of(PlanDraft.Step.drive(drive, arrival),
                PlanDraft.Step.stay(PlanStepKind.NEW_TARGET, stay, place.name()));
        OffsetDateTime planned = run.plannedArrival();
        Duration delay = arrival == null || planned == null ? null : Duration.between(planned, arrival);
        PlanResult result = new PlanResult(null, false,
                0, costs.total(), costs.currency(), affects, place.name());
        return new Composed(new PlanDraft(steps, drive.departureAt(), arrival, delay, null, drive.conflicts(), result,
                List.of()), problems);
    }

    private record Costs(BigDecimal total, @Nullable String currency) {
    }

    private static Costs costs(List<ProposalStay> stays, List<String> problems) {
        BigDecimal total = BigDecimal.ZERO;
        String currency = null;
        for (ProposalStay stay : stays) {
            if (currency != null && !currency.equals(stay.currency())) {
                problems.add("CURRENCIES_DIFFER");
            }
            currency = stay.currency();
            total = total.add(stay.price());
        }
        return new Costs(total, currency);
    }

    /**
     * The latest check-in of the booked accommodation on its first day (before {@link #NIGHT_ENDS}: the night after),
     * else the arrival time of the target stop.
     */
    static @Nullable OffsetDateTime deadline(RunContext run, @Nullable BookedStay booked, ZoneOffset offset) {
        if (booked != null && booked.checkInUntil() != null) {
            LocalDate day = booked.checkIn() != null ? booked.checkIn()
                    : run.deadline() != null ? run.deadline().atZoneSameInstant(offset).toLocalDate() : null;
            if (day != null) {
                LocalDate until = booked.checkInUntil().isBefore(NIGHT_ENDS) ? day.plusDays(1) : day;
                return OffsetDateTime.of(until, booked.checkInUntil(), offset);
            }
        }
        return run.deadline();
    }

    private static ProposalDraft drive(RunContext run, @Nullable String id) {
        if (id == null || RunContext.KEEP_ROUTE.equals(id)) {
            return run.keepRoute();
        }
        ProposalDraft option = run.option(id).orElseThrow(() -> new ToolInputException("unknown drive " + id));
        if (option.kind() != ProposalKind.REROUTE && option.kind() != ProposalKind.SHIFT_DEPARTURE) {
            throw new ToolInputException("drive must be keep_route, a route_ or a departure_ option");
        }
        return option;
    }

    private static @Nullable String text(JsonNode arguments, String field) {
        JsonNode value = arguments.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
}
