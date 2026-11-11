package com.nfv.advisor.logic;

import com.nfv.advisor.data.AdvisorPlanExecutionRepository;
import com.nfv.advisor.data.AdvisorRunRepository;
import com.nfv.advisor.entities.AdvisorPlanExecution;
import com.nfv.advisor.entities.AdvisorProposal;
import com.nfv.advisor.entities.AdvisorRun;
import com.nfv.advisor.entities.PlanExecutionStatus;
import com.nfv.advisor.entities.PlanStepKind;
import com.nfv.advisor.entities.ProposalKind;
import com.nfv.advisor.entities.ProposalStay;
import com.nfv.bookings.entities.PlanPaymentStatus;
import com.nfv.bookings.logic.BookingTodos;
import com.nfv.bookings.logic.PlanPayments;
import com.nfv.recommendations.logic.InvalidSuggestionInputException;
import com.nfv.recommendations.logic.RouteChoice;
import com.nfv.trips.entities.Route;
import com.nfv.trips.entities.Stop;
import com.nfv.trips.logic.InvalidTripInputException;
import com.nfv.trips.logic.NewStop;
import com.nfv.trips.logic.TripEditing;
import com.nfv.trips.logic.TripItemNotFoundException;
import com.nfv.trips.logic.TripLogic;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Takes a plan with one click, see Docs/incident-plans.md#ausführen-mit-einem-klick: hotels first (one PayPal approval,
 * {@link PlanPayments}), then the trip changes, then the hotels are paid at the stops the plan made. A plan without
 * hotels changes the trip right away. The model is not involved.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlanExecutions {
    private final AdvisorPlanExecutionRepository repository;
    private final AdvisorRunRepository runRepository;
    private final PlanPayments planPayments;
    private final RouteChoice routeChoice;
    private final TripEditing tripEditing;
    private final TripLogic tripLogic;
    private final BookingTodos todos;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * Where taking a plan stands.
     *
     * @param payment null for a plan without hotels
     */
    public record ExecutionView(long executionId, long tripId, PlanExecutionStatus status, @Nullable String failure,
                                @Nullable PlanPayments.PlanPaymentView payment, List<Long> plannedRouteIds,
                                int todosAdded) {
    }

    /** What the plan changed, stored as JSON at the execution. */
    record Outcome(List<Long> plannedRouteIds, Map<String, Long> stopIdByKey, int todosAdded) {
    }

    private record Chosen(long tripId, long routeId, long targetStopId, PlanSnapshot plan) {
    }

    /**
     * Prebooks the hotels of the plan and creates the one PayPal order for them. Nothing is paid or changed yet; the
     * user approves at PayPal, then {@link #complete}.
     *
     * @throws TripItemNotFoundException     run or route unknown or of another user
     * @throws InvalidProposalInputException no proposal with this rank
     * @throws ProposalNotApplicableException not a plan with hotels, or the run is not finished
     */
    public ExecutionView checkout(long appUserId, long runId, int rank, PlanPayments.Guest guest) {
        Chosen chosen = chosen(appUserId, runId, rank);
        List<PlanSnapshot.Step> stays = chosen.plan().stays();
        if (stays.isEmpty()) {
            throw new ProposalNotApplicableException("the plan has no hotel to pay, take it with acceptance");
        }
        List<PlanPayments.PlanStay> paid = stays.stream().map(step -> {
            ProposalStay stay = Objects.requireNonNull(step.stay());
            return new PlanPayments.PlanStay(PlanSnapshot.key(step), chosen.targetStopId(), stay.replacesBookingId(),
                    stay.hotelId(), stay.offerId(), stay.checkIn(), stay.checkOut(), stay.guests(), stay.rooms(),
                    stay.price(), stay.currency());
        }).toList();
        // first, so that the way back from PayPal can name it
        long executionId = Objects.requireNonNull(transactionTemplate.execute(_ -> repository.save(
                new AdvisorPlanExecution(appUserId, chosen.tripId(), chosen.routeId(), runId, rank,
                        chosen.plan().json(), true, OffsetDateTime.now(clock))).getId()));
        PlanPayments.PlanPaymentView payment;
        try {
            payment = planPayments.reserve(appUserId, paid, guest, "/reisen/" + chosen.tripId() + "/plan/" + executionId);
        } catch (RuntimeException e) {
            transactionTemplate.executeWithoutResult(_ -> repository.findById(executionId).orElseThrow()
                    .failed("hotels not reserved: " + e.getMessage()));
            throw e;
        }
        transactionTemplate.executeWithoutResult(_ -> repository.findById(executionId).orElseThrow()
                .paidWith(payment.planPaymentId()));
        return view(executionId, payment);
    }

    /**
     * After the approval at PayPal: books every hotel, changes the trip and pays. Safe to repeat.
     *
     * @throws com.nfv.bookings.logic.PaymentFailedException    not approved yet or declined
     * @throws com.nfv.bookings.logic.OfferUnavailableException a hotel refused, nothing is paid or changed
     */
    public ExecutionView complete(long appUserId, long executionId) {
        AdvisorPlanExecution execution = owned(appUserId, executionId);
        if (execution.getPlanPaymentId() != null && execution.getStatus() == PlanExecutionStatus.AWAITING_PAYMENT) {
            try {
                // applies the plan through PlanPaymentSecured
                planPayments.secure(appUserId, execution.getPlanPaymentId());
            } catch (RuntimeException e) {
                failIfPaymentFailed(execution);
                throw e;
            }
            failIfPaymentFailed(execution);
        }
        return view(executionId, null);
    }

    /** The user cancelled at PayPal: nothing is paid or changed. */
    public ExecutionView abort(long appUserId, long executionId) {
        AdvisorPlanExecution execution = owned(appUserId, executionId);
        if (execution.getPlanPaymentId() != null) {
            planPayments.abort(appUserId, execution.getPlanPaymentId());
            failIfPaymentFailed(execution);
        }
        return view(executionId, null);
    }

    public ExecutionView get(long appUserId, long executionId) {
        owned(appUserId, executionId);
        return view(executionId, null);
    }

    /**
     * Every hotel is booked and the money reserved: the plan changes the trip, then the hotels are paid at its stops.
     * Also from the background job; a plan that was applied before is only paid.
     */
    @EventListener
    void secured(PlanPayments.PlanPaymentSecured event) {
        try {
            Optional<AdvisorPlanExecution> found = transactionTemplate.execute(_ ->
                    repository.findByPlanPaymentId(event.planPaymentId()));
            if (found == null || found.isEmpty()) {
                return;
            }
            AdvisorPlanExecution execution = found.get();
            switch (execution.getStatus()) {
                case AWAITING_PAYMENT -> applyPaid(execution);
                case APPLIED -> planPayments.settle(execution.getAppUserId(), event.planPaymentId(),
                        outcome(execution).stopIdByKey());
                case APPLIED_PARTLY -> planPayments.settle(execution.getAppUserId(), event.planPaymentId(), Map.of());
                default -> {
                }
            }
        } catch (RuntimeException e) {
            log.warn("Plan payment {} secured, but the plan was not finished: {}", event.planPaymentId(),
                    e.getMessage(), e);
        }
    }

    private void applyPaid(AdvisorPlanExecution execution) {
        long executionId = execution.getId();
        boolean claimed = Boolean.TRUE.equals(transactionTemplate.execute(_ ->
                repository.findById(executionId).orElseThrow().claim()));
        if (!claimed) {
            return;
        }
        long paymentId = Objects.requireNonNull(execution.getPlanPaymentId());
        Outcome outcome;
        try {
            outcome = apply(execution.getAppUserId(), execution.getTripId(), execution.getRouteId(),
                    PlanSnapshot.parse(execution.getPlan()));
        } catch (RuntimeException e) {
            // the hotels are booked: they are paid at the stops they were booked for, the user plans by hand
            log.warn("Plan execution {}: trip not changed: {}", executionId, e.getMessage());
            transactionTemplate.executeWithoutResult(_ -> repository.findById(executionId).orElseThrow()
                    .appliedPartly("trip not changed: " + e.getMessage(), OffsetDateTime.now(clock)));
            planPayments.settle(execution.getAppUserId(), paymentId, Map.of());
            return;
        }
        String json = PlanSnapshot.JSON.writeValueAsString(outcome);
        transactionTemplate.executeWithoutResult(_ -> repository.findById(executionId).orElseThrow()
                .applied(json, OffsetDateTime.now(clock)));
        accepted(execution.getRunId(), execution.getProposalRank());
        planPayments.settle(execution.getAppUserId(), paymentId, outcome.stopIdByKey());
    }

    /** A plan without hotels, taken through the acceptance of the proposal. */
    Outcome applyFree(long appUserId, long tripId, long routeId, long runId, int rank, PlanSnapshot plan) {
        long executionId = Objects.requireNonNull(transactionTemplate.execute(_ -> repository.save(
                new AdvisorPlanExecution(appUserId, tripId, routeId, runId, rank, plan.json(), false,
                        OffsetDateTime.now(clock))).getId()));
        Outcome outcome = apply(appUserId, tripId, routeId, plan);
        String json = PlanSnapshot.JSON.writeValueAsString(outcome);
        transactionTemplate.executeWithoutResult(_ -> repository.findById(executionId).orElseThrow()
                .applied(json, OffsetDateTime.now(clock)));
        return outcome;
    }

    /**
     * Changes the trip as the plan says: the drive (detour or departure), a stop at the hotel of the night with the way
     * on as checked, the target moved to another place, late arrivals on the to-do list.
     *
     * @throws ProposalNotApplicableException the trip does not allow it any more
     */
    Outcome apply(long appUserId, long tripId, long routeId, PlanSnapshot plan) {
        try {
            return applySteps(appUserId, tripId, routeId, plan);
        } catch (InvalidSuggestionInputException | InvalidTripInputException e) {
            throw new ProposalNotApplicableException(e.getMessage());
        }
    }

    private Outcome applySteps(long appUserId, long tripId, long routeId, PlanSnapshot plan) {
        Route route = tripLogic.route(appUserId, tripId, routeId);
        long fromId = route.getFromStop().getId();
        long toId = route.getToStop().getId();
        List<Stop> stops = tripLogic.stops(appUserId, tripId);
        Stop from = stops.stream().filter(stop -> stop.getId() == fromId).findFirst().orElseThrow();
        Stop target = stops.stream().filter(stop -> stop.getId() == toId).findFirst().orElseThrow();
        ZoneOffset offset = target.getArrivalAt() != null ? target.getArrivalAt().getOffset()
                : from.getDepartureAt() != null ? from.getDepartureAt().getOffset() : OffsetDateTime.now(clock).getOffset();
        PlanSnapshot.Step drive = step(plan, PlanStepKind.DRIVE);
        PlanSnapshot.Step overnight = step(plan, PlanStepKind.OVERNIGHT);
        PlanSnapshot.Step continuation = step(plan, PlanStepKind.CONTINUE);
        PlanSnapshot.Step keep = step(plan, PlanStepKind.KEEP_BOOKING);
        PlanSnapshot.Step newTarget = step(plan, PlanStepKind.NEW_TARGET);
        Set<Long> planned = new LinkedHashSet<>();
        Map<String, Long> stopIdByKey = new LinkedHashMap<>();
        int added = 0;

        if (newTarget != null) {
            ProposalStay stay = Objects.requireNonNull(newTarget.stay());
            OffsetDateTime arrival = drive == null || drive.arrivalAt() == null ? null
                    : drive.arrivalAt().withOffsetSameInstant(offset);
            OffsetDateTime departure = target.getDepartureAt();
            if (departure != null && arrival != null && !departure.isAfter(arrival)) {
                departure = OffsetDateTime.of(stay.checkOut(), departure.withOffsetSameInstant(offset).toLocalTime(),
                        offset);
            }
            planned.addAll(tripEditing.updateStop(appUserId, tripId, target.getId(), new NewStop(
                    Objects.requireNonNullElse(newTarget.placeName(), stay.place()), stay.latitude(), stay.longitude(),
                    arrival, departure)).keySet());
            if (drive != null && !drive.points().isEmpty()
                    && routeChoice.choose(appUserId, routeId, drive.points()).result() != null) {
                planned.add(routeId);
            }
            stopIdByKey.put("target", target.getId());
        } else if (overnight != null) {
            ProposalStay stay = Objects.requireNonNull(overnight.stay());
            OffsetDateTime departure = continuation != null && continuation.departureAt() != null
                    ? continuation.departureAt().withOffsetSameInstant(offset)
                    : OffsetDateTime.of(stay.checkOut(), ProposalDecisions.STOPOVER_DEPARTURE, offset);
            OffsetDateTime arrival = drive == null || drive.arrivalAt() == null ? null
                    : drive.arrivalAt().withOffsetSameInstant(offset);
            if (arrival != null && !departure.isAfter(arrival)) {
                arrival = null;
            }
            int position = stops.indexOf(target);
            planned.addAll(tripEditing.addStop(appUserId, tripId, new NewStop(
                    Objects.requireNonNullElse(overnight.placeName(), stay.place()), stay.latitude(), stay.longitude(),
                    arrival, departure), position).keySet());
            long stayStopId = tripLogic.stops(appUserId, tripId).get(position).getId();
            stopIdByKey.put("overnight", stayStopId);
            if (continuation != null && !continuation.points().isEmpty()) {
                tripLogic.routes(appUserId, tripId).stream().filter(r -> r.getFromStop().getId() == stayStopId)
                        .findFirst().map(Route::getId).ifPresent(next -> {
                            if (routeChoice.choose(appUserId, next, continuation.points()).result() != null) {
                                planned.add(next);
                            }
                        });
            }
            if (continuation != null && continuation.arrivalAt() != null) {
                OffsetDateTime later = continuation.arrivalAt().withOffsetSameInstant(offset);
                if ((target.getArrivalAt() == null || later.isAfter(target.getArrivalAt()))
                        && (target.getDepartureAt() == null || later.isBefore(target.getDepartureAt()))) {
                    planned.addAll(tripEditing.updateStop(appUserId, tripId, target.getId(), new NewStop(
                            target.getName(), target.getLatitude(), target.getLongitude(), later,
                            target.getDepartureAt())).keySet());
                }
            }
        } else if (drive != null && drive.routeKind() == ProposalKind.REROUTE) {
            if (routeChoice.choose(appUserId, routeId, drive.points()).result() != null) {
                planned.add(routeId);
            }
        } else if (drive != null && drive.routeKind() == ProposalKind.SHIFT_DEPARTURE && drive.departureAt() != null) {
            if (routeChoice.chooseDeparture(appUserId, routeId, drive.departureAt()).result() != null) {
                planned.add(routeId);
            }
        }
        if (keep != null && keep.arrivalOn() != null) {
            added += todos.lateArrival(appUserId, target.getId(), keep.arrivalOn()).size();
        }
        if (step(plan, PlanStepKind.REPLACE_STAY) != null) {
            stopIdByKey.put("target", target.getId());
        }
        log.info("Plan for route {} taken: {} routes planned, {} to-dos", routeId, planned.size(), added);
        return new Outcome(new ArrayList<>(planned), stopIdByKey, added);
    }

    private static @Nullable PlanSnapshot.Step step(PlanSnapshot plan, PlanStepKind kind) {
        return plan.steps().stream().filter(step -> step.kind() == kind).findFirst().orElse(null);
    }

    private Chosen chosen(long appUserId, long runId, int rank) {
        long routeId = Objects.requireNonNull(transactionTemplate.execute(_ -> runRepository.findById(runId)
                .map(AdvisorRun::getRouteId).orElseThrow(() -> new TripItemNotFoundException("run", runId))));
        Long tripId = tripLogic.tripIdsByRouteId(appUserId).get(routeId);
        if (tripId == null) {
            throw new TripItemNotFoundException("route", routeId);
        }
        long targetStopId = tripLogic.route(appUserId, tripId, routeId).getToStop().getId();
        PlanSnapshot plan = Objects.requireNonNull(transactionTemplate.execute(_ -> {
            AdvisorRun run = runRepository.findById(runId).orElseThrow(() -> new TripItemNotFoundException("run", runId));
            if (!run.isFinished()) {
                throw new ProposalNotApplicableException("run " + runId + " is " + run.getStatus());
            }
            if (rank < 1 || rank > run.getProposals().size()) {
                throw new InvalidProposalInputException("run " + runId + " has no proposal " + rank);
            }
            AdvisorProposal proposal = run.getProposals().get(rank - 1);
            if (proposal.getKind() != ProposalKind.PLAN) {
                throw new ProposalNotApplicableException("proposal " + rank + " is no plan");
            }
            return PlanSnapshot.of(proposal);
        }));
        return new Chosen(tripId, routeId, targetStopId, plan);
    }

    /** The run is gone if a night on the way split its route. */
    private void accepted(long runId, int rank) {
        transactionTemplate.executeWithoutResult(_ -> runRepository.findById(runId)
                .ifPresent(run -> run.accepted(rank - 1, OffsetDateTime.now(clock))));
    }

    private void failIfPaymentFailed(AdvisorPlanExecution execution) {
        long paymentId = Objects.requireNonNull(execution.getPlanPaymentId());
        PlanPayments.PlanPaymentView payment = planPayments.get(execution.getAppUserId(), paymentId);
        if (payment.status() == PlanPaymentStatus.FAILED) {
            String reason = Objects.requireNonNullElse(payment.failure(), "payment failed");
            transactionTemplate.executeWithoutResult(_ -> repository.findById(execution.getId()).orElseThrow()
                    .failed(reason));
        }
    }

    private AdvisorPlanExecution owned(long appUserId, long executionId) {
        return Objects.requireNonNull(transactionTemplate.execute(_ -> repository.findById(executionId)
                .filter(execution -> execution.getAppUserId() == appUserId)
                .orElseThrow(() -> new TripItemNotFoundException("plan execution", executionId))));
    }

    private static Outcome outcome(AdvisorPlanExecution execution) {
        return execution.getOutcome() == null ? new Outcome(List.of(), Map.of(), 0)
                : PlanSnapshot.JSON.readValue(execution.getOutcome(), Outcome.class);
    }

    private ExecutionView view(long executionId, @Nullable PlanPayments.PlanPaymentView payment) {
        AdvisorPlanExecution execution = Objects.requireNonNull(transactionTemplate.execute(_ ->
                repository.findById(executionId).orElseThrow()));
        PlanPayments.PlanPaymentView current = payment != null ? payment : execution.getPlanPaymentId() == null ? null
                : planPayments.get(execution.getAppUserId(), execution.getPlanPaymentId());
        Outcome outcome = outcome(execution);
        return new ExecutionView(executionId, execution.getTripId(), execution.getStatus(), execution.getFailure(),
                current, outcome.plannedRouteIds(), outcome.todosAdded());
    }
}
