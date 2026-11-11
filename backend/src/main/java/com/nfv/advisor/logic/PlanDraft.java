package com.nfv.advisor.logic;

import com.nfv.advisor.entities.AdvisorProposal;
import com.nfv.advisor.entities.AdvisorProposalStep;
import com.nfv.advisor.entities.PlanResult;
import com.nfv.advisor.entities.PlanStepKind;
import com.nfv.advisor.entities.ProposalConflict;
import com.nfv.advisor.entities.ProposalKind;
import com.nfv.advisor.entities.ProposalPoint;
import com.nfv.advisor.entities.ProposalStay;
import jakarta.annotation.Nullable;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.IntStream;

/**
 * A plan as {@code compose_plan} computed it, before the model adds its reason, see
 * Docs/incident-plans.md#bausteine-und-compose_plan.
 *
 * @param departureAt departure of the first drive
 * @param arrivalAt   at the target (the new one with {@code NEW_TARGET}), null if it is not reached
 * @param delay       against the planned arrival, null without one
 */
record PlanDraft(List<Step> steps, @Nullable OffsetDateTime departureAt, @Nullable OffsetDateTime arrivalAt,
                 @Nullable Duration delay, @Nullable Boolean meetsDeadline, List<ProposalConflict> conflicts,
                 PlanResult result, List<String> warnings) {

    /** A step, see {@link AdvisorProposalStep} for the fields. */
    record Step(PlanStepKind kind, @Nullable ProposalKind routeKind, @Nullable String via,
                @Nullable OffsetDateTime departureAt, @Nullable OffsetDateTime arrivalAt,
                @Nullable OffsetDateTime waitedFor, @Nullable Double distanceMeters, @Nullable Duration duration,
                @Nullable String placeName, @Nullable Double latitude, @Nullable Double longitude,
                @Nullable Long bookingId, @Nullable LocalDate arrivalOn, List<ProposalPoint> points,
                @Nullable ProposalStay stay) {

        static Step drive(ProposalDraft route, @Nullable OffsetDateTime arrivalAt) {
            return new Step(PlanStepKind.DRIVE, route.kind(), route.via(), route.departureAt(), arrivalAt, null,
                    route.distanceMeters(), route.duration(), null, null, null, null, null, route.points(), null);
        }

        /** The way on after a night, {@code waitedFor} if it leaves when a closure ends. */
        static Step continuation(ProposalDraft route, @Nullable OffsetDateTime waitedFor) {
            return new Step(PlanStepKind.CONTINUE, route.kind(), route.via(), route.departureAt(), route.arrivalAt(),
                    waitedFor, route.distanceMeters(), route.duration(), null, null, null, null, null, route.points(),
                    null);
        }

        static Step stay(PlanStepKind kind, ProposalStay stay, @Nullable String placeName) {
            return new Step(kind, null, null, null, null, null, null, null, placeName, stay.latitude(),
                    stay.longitude(), null, null, List.of(), stay);
        }

        static Step keepBooking(long bookingId, @Nullable LocalDate arrivalOn, OffsetDateTime arrivalAt) {
            return new Step(PlanStepKind.KEEP_BOOKING, null, null, null, arrivalAt, null, null, null, null, null, null,
                    bookingId, arrivalOn, List.of(), null);
        }

        AdvisorProposalStep toEntity(int position) {
            return new AdvisorProposalStep(position, kind, routeKind, via, departureAt, arrivalAt, waitedFor,
                    distanceMeters, duration, placeName, latitude, longitude, bookingId, arrivalOn, points, stay);
        }
    }

    AdvisorProposal toProposal(int position, @Nullable String reason) {
        return new AdvisorProposal(position, reason, departureAt, arrivalAt, delay, meetsDeadline, conflicts, result,
                IntStream.range(0, steps.size()).mapToObj(i -> steps.get(i).toEntity(i)).toList());
    }

    /** The plan as the model sees it. */
    ObjectNode describe(JsonMapper json, @Nullable String id, List<String> problems) {
        ObjectNode node = json.createObjectNode();
        node.put("plan_id", id);
        ArrayNode list = node.putArray("steps");
        for (Step step : steps) {
            ObjectNode item = list.addObject().put("step", step.kind().name());
            if (step.routeKind() != null) {
                item.put("how", step.routeKind().name());
            }
            item.put("via", step.via());
            item.put("departure_at", text(step.departureAt()));
            item.put("arrival_at", text(step.arrivalAt()));
            if (step.waitedFor() != null) {
                item.put("waits_for_closure_to_end", true);
            }
            item.put("place", step.placeName());
            if (step.stay() != null) {
                item.put("hotel", step.stay().hotelName());
                item.put("check_in", step.stay().checkIn().toString());
                item.put("check_out", step.stay().checkOut().toString());
                item.put("price", step.stay().price());
                item.put("currency", step.stay().currency());
            }
            if (step.arrivalOn() != null) {
                item.put("tell_accommodation_arrival_on", step.arrivalOn().toString());
            }
        }
        node.put("departure_at", text(departureAt));
        node.put("arrival_at", text(arrivalAt));
        node.put("deadline", text(result.deadline()));
        node.put("meets_deadline", meetsDeadline);
        node.put("delay_minutes", delay == null ? null : delay.toMinutes());
        node.put("reaches_planned_target", result.reachesTarget());
        node.put("new_target", result.newTargetName());
        node.put("extra_nights", result.extraNights());
        node.put("extra_cost", result.extraCost());
        node.put("currency", result.costCurrency());
        node.put("later_stops_move", result.affectsFollowing());
        ArrayNode problemList = node.putArray("problems");
        problems.forEach(problemList::add);
        ArrayNode warningList = node.putArray("warnings");
        warnings.forEach(warningList::add);
        return node;
    }

    private static @Nullable String text(@Nullable OffsetDateTime time) {
        return time == null ? null : time.toString();
    }
}
