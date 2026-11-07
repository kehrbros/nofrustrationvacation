package com.nfv.advisor.logic;

import com.nfv.advisor.entities.AdvisorProposal;
import com.nfv.advisor.entities.ProposalConflict;
import com.nfv.advisor.entities.ProposalKind;
import com.nfv.advisor.entities.ProposalPoint;
import com.nfv.advisor.entities.ProposalStay;
import com.nfv.incidents.entities.Impact;
import com.nfv.incidents.logic.RouteConsequence;
import com.nfv.network.logic.RoutedSegment;
import com.nfv.recommendations.logic.OptionKind;
import com.nfv.recommendations.logic.SuggestedOption;
import com.nfv.trips.logic.RouteSchedule;
import jakarta.annotation.Nullable;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * An option as calculated by the backend, before the model adds its reason.
 *
 * @param path driven segments for tools that look along the option, neither stored nor shown to the model
 * @param stay the offer, {@code STOPOVER} and {@code TARGET_STAY} only
 */
record ProposalDraft(ProposalKind kind, @Nullable String via, @Nullable OffsetDateTime departureAt,
                     @Nullable OffsetDateTime arrivalAt, @Nullable Double distanceMeters, @Nullable Duration duration,
                     @Nullable Duration delay, @Nullable Double sharedRatio, @Nullable Boolean meetsDeadline,
                     List<ProposalPoint> points, List<ProposalConflict> conflicts, List<RoutedSegment> path,
                     @Nullable ProposalStay stay) {

    static ProposalDraft of(SuggestedOption option, @Nullable OffsetDateTime deadline) {
        return new ProposalDraft(option.kind() == OptionKind.DEPARTURE_SHIFT ? ProposalKind.SHIFT_DEPARTURE
                : ProposalKind.REROUTE, option.via(), option.departureAt(), option.arrivalAt(), option.distanceMeters(),
                option.duration(), option.delay(), option.sharedRatio(),
                deadline == null ? null : !option.arrivalAt().isAfter(deadline),
                option.points().stream().map(p -> new ProposalPoint(p.latitude(), p.longitude(), p.roadHint(),
                        p.waypointId())).toList(),
                option.conflicts().stream().map(c -> new ProposalConflict(c.incidentId(), c.impact(), c.delay()))
                        .toList(),
                option.path(), null);
    }

    /**
     * Staying on the planned route, with the numbers of the assessment: unlike the reference of the suggestions they
     * include the wait at a closure. No arrival if a closure cannot be passed.
     *
     * @param counting the consequences that count on the route
     */
    static ProposalDraft keepRoute(RouteSchedule schedule, List<RouteConsequence> counting) {
        boolean unpassable = counting.stream().anyMatch(c -> c.impact() == Impact.BLOCKED && c.delay() == null);
        OffsetDateTime arrival = unpassable ? null : counting.stream().map(RouteConsequence::expectedArrival)
                .filter(Objects::nonNull).max(OffsetDateTime::compareTo).orElse(schedule.plannedArrival());
        Duration delay = arrival == null || schedule.plannedArrival() == null ? null
                : Duration.between(schedule.plannedArrival(), arrival);
        Boolean meets = schedule.deadline() == null ? null : arrival != null && !arrival.isAfter(schedule.deadline());
        return new ProposalDraft(ProposalKind.KEEP_ROUTE, null, schedule.departure(), arrival, null, null, delay, null,
                meets, List.of(), counting.stream()
                .map(c -> new ProposalConflict(c.incidentId(), c.impact(), c.delay())).toList(), List.of(), null);
    }

    /**
     * A night along {@code route} (keep_route or a route_ option): drive it as far as the area, so its points, departure
     * and incidents stay. Arrival is the approximate one at the area, the way to the hotel is not routed.
     */
    static ProposalDraft stopover(ProposalDraft route, OffsetDateTime arrivalAtArea, ProposalStay stay) {
        return new ProposalDraft(ProposalKind.STOPOVER, route.via, route.departureAt, arrivalAtArea, null, null, null,
                route.sharedRatio, null, route.points, route.conflicts, List.of(), stay);
    }

    /** Another accommodation at the target stop: the planned route stays, with its numbers. */
    static ProposalDraft targetStay(ProposalDraft keepRoute, ProposalStay stay) {
        return new ProposalDraft(ProposalKind.TARGET_STAY, null, keepRoute.departureAt, keepRoute.arrivalAt, null,
                null, keepRoute.delay, null, keepRoute.meetsDeadline, List.of(), keepRoute.conflicts, List.of(), stay);
    }

    AdvisorProposal toProposal(int position, @Nullable String reason) {
        return new AdvisorProposal(position, kind, reason, via, departureAt, arrivalAt, distanceMeters, duration, delay,
                sharedRatio, meetsDeadline, points, conflicts, stay);
    }

    /** The option as the model sees it. No geometry, it would only cost tokens. */
    ObjectNode describe(JsonMapper json, String id) {
        ObjectNode node = json.createObjectNode();
        node.put("option_id", id);
        node.put("kind", kind.name());
        node.put("via", via);
        node.put("departure_at", departureAt == null ? null : departureAt.toString());
        node.put("arrival_at", arrivalAt == null ? null : arrivalAt.toString());
        node.put("distance_km", distanceMeters == null ? null : Math.round(distanceMeters / 100) / 10.0);
        node.put("driving_minutes", duration == null ? null : duration.toMinutes());
        node.put("delay_minutes", delay == null ? null : delay.toMinutes());
        node.put("shared_with_planned_route", sharedRatio == null ? null : Math.round(sharedRatio * 100) / 100.0);
        node.put("meets_deadline", meetsDeadline);
        if (stay != null) {
            ObjectNode hotel = node.putObject("stay");
            hotel.put("hotel", stay.hotelName());
            hotel.put("place", stay.place());
            hotel.put("distance_km", Math.round(stay.distanceMeters() / 100.0) / 10.0);
            hotel.put("check_in", stay.checkIn().toString());
            hotel.put("check_out", stay.checkOut().toString());
            hotel.put("price", stay.price());
            hotel.put("currency", stay.currency());
            hotel.put("refundable", stay.refundable());
            hotel.put("free_cancellation_until",
                    stay.freeCancellationUntil() == null ? null : stay.freeCancellationUntil().toString());
            hotel.put("replaces_booked_stay", stay.replacesBookingId() != null);
        }
        ArrayNode passed = node.putArray("incidents_passed");
        conflicts.forEach(conflict -> {
            ObjectNode item = passed.addObject().put("incident_id", conflict.getIncidentId())
                    .put("impact", conflict.getImpact().name());
            item.put("delay_minutes", conflict.getDelay() == null ? null : conflict.getDelay().toMinutes());
        });
        return node;
    }
}
