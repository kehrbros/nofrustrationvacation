package com.nfv.advisor.logic;

import com.nfv.advisor.data.AdvisorRunRepository;
import com.nfv.advisor.entities.AdvisorProposal;
import com.nfv.advisor.entities.AdvisorRun;
import com.nfv.advisor.entities.ProposalKind;
import com.nfv.advisor.entities.ProposalStay;
import com.nfv.bookings.logic.BookingTodos;
import com.nfv.recommendations.logic.InvalidSuggestionInputException;
import com.nfv.recommendations.logic.RouteChoice;
import com.nfv.recommendations.logic.SuggestedOption;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * What the user does with the proposals of a run, see Docs/advisor.md#auswählen-und-verwerfen: take one, the trip is
 * changed accordingly, or dismiss all and plan again by hand. Bookings are never made here, an accommodation is booked
 * by the user afterwards (PayPal). The model is not involved.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProposalDecisions {
    /** Departure after a night on the way, local at the stop. */
    static final LocalTime STOPOVER_DEPARTURE = LocalTime.of(9, 0);

    private final AdvisorRunRepository runRepository;
    private final RouteChoice routeChoice;
    private final TripEditing tripEditing;
    private final TripLogic tripLogic;
    private final BookingTodos todos;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * What taking a proposal changed.
     *
     * @param plannedRouteIds   routes planned again
     * @param stayStopId        {@code STOPOVER} and {@code TARGET_STAY}: the stop to book the hotel at, the next step
     * @param replacesBookingId {@code TARGET_STAY}: the booking the hotel replaces, booked like an alternative to it
     * @param todosAdded        new entries on the to-do list of the trip
     */
    public record Accepted(long runId, int rank, ProposalKind kind, long tripId, List<Long> plannedRouteIds,
                           @Nullable Long stayStopId, @Nullable Long replacesBookingId, int todosAdded) {
    }

    private record Chosen(long routeId, AdvisorProposal proposal, int position, List<SuggestedOption.Point> points) {
    }

    /**
     * Takes the proposal: {@code REROUTE} and {@code SHIFT_DEPARTURE} change the route like a choice of the user,
     * {@code KEEP_ROUTE} changes nothing, {@code STOPOVER} adds a stop at the hotel before the target stop (the route is
     * split and both parts are planned), {@code TARGET_STAY} only leads to booking. Taking another proposal of the same
     * run later replaces the decision. Also allowed if the run is stale, planning then assesses the new way.
     *
     * @param rank 1 is the best
     * @throws TripItemNotFoundException      run or route unknown or of another user
     * @throws InvalidProposalInputException  no proposal with this rank
     * @throws ProposalNotApplicableException run not finished, or the trip does not allow it any more
     */
    public Accepted accept(long appUserId, long runId, int rank) {
        long routeId = routeOf(runId);
        // no RouteSnapshot here: it would keep the sections of a route that a stopover deletes in the persistence
        // context of the request (open in view)
        Long tripId = tripLogic.tripIdsByRouteId(appUserId).get(routeId);
        if (tripId == null) {
            throw new TripItemNotFoundException("route", routeId);
        }
        Ends ends = ends(appUserId, tripId, routeId);
        Chosen chosen = Objects.requireNonNull(transactionTemplate.execute(_ -> {
            AdvisorRun run = runRepository.findById(runId).orElseThrow(() -> new TripItemNotFoundException("run", runId));
            if (!run.isFinished()) {
                throw new ProposalNotApplicableException("run " + runId + " is " + run.getStatus());
            }
            if (rank < 1 || rank > run.getProposals().size()) {
                throw new InvalidProposalInputException("run " + runId + " has no proposal " + rank);
            }
            AdvisorProposal proposal = run.getProposals().get(rank - 1);
            return new Chosen(routeId, proposal, rank - 1, proposal.getPoints().stream()
                    .map(p -> new SuggestedOption.Point(p.getLatitude(), p.getLongitude(), p.getRoadHint(),
                            p.getWaypointId())).toList());
        }));
        AdvisorProposal proposal = chosen.proposal();
        Accepted accepted;
        try {
            accepted = switch (proposal.getKind()) {
                case REROUTE -> planned(runId, rank, proposal.getKind(), tripId, routeId,
                        routeChoice.choose(appUserId, routeId, chosen.points()).result() != null);
                case SHIFT_DEPARTURE -> {
                    if (proposal.getDepartureAt() == null) {
                        throw new ProposalNotApplicableException("proposal has no departure");
                    }
                    yield planned(runId, rank, proposal.getKind(), tripId, routeId,
                            routeChoice.chooseDeparture(appUserId, routeId, proposal.getDepartureAt()).result() != null);
                }
                case KEEP_ROUTE -> new Accepted(runId, rank, proposal.getKind(), tripId, List.of(), null, null, 0);
                case TARGET_STAY -> new Accepted(runId, rank, proposal.getKind(), tripId, List.of(), ends.to().getId(),
                        stay(proposal).replacesBookingId(), 0);
                case STOPOVER -> stopover(appUserId, runId, rank, tripId, ends, proposal);
            };
        } catch (InvalidSuggestionInputException | InvalidTripInputException e) {
            throw new ProposalNotApplicableException(e.getMessage());
        }
        if (proposal.getKind() != ProposalKind.STOPOVER) {
            // a stopover replaced the route, the database removed the run with it
            transactionTemplate.executeWithoutResult(_ -> runRepository.findById(runId)
                    .ifPresent(run -> run.accepted(chosen.position(), OffsetDateTime.now(clock))));
        }
        return accepted;
    }

    /**
     * None fits: the user plans again by hand. Obsolete bookings come on the to-do list when stops are deleted. New
     * proposals can be asked for at any time.
     *
     * @throws TripItemNotFoundException run unknown or of another user
     */
    public void dismiss(long appUserId, long runId) {
        long routeId = routeOf(runId);
        if (!tripLogic.tripIdsByRouteId(appUserId).containsKey(routeId)) {
            throw new TripItemNotFoundException("route", routeId);
        }

        transactionTemplate.executeWithoutResult(_ -> runRepository.findById(runId)
                .orElseThrow(() -> new TripItemNotFoundException("run", runId))
                .dismissed(OffsetDateTime.now(clock)));
    }

    private Accepted planned(long runId, int rank, ProposalKind kind, long tripId, long routeId, boolean planned) {
        return new Accepted(runId, rank, kind, tripId, planned ? List.of(routeId) : List.of(), null, null, 0);
    }

    /** Start and target stop of the route, with the stops of the trip in their order. */
    private record Ends(Stop from, Stop to, List<Stop> stops) {
    }

    private Ends ends(long appUserId, long tripId, long routeId) {
        Route route = tripLogic.route(appUserId, tripId, routeId);
        // ids only, the stops are proxies
        long fromId = route.getFromStop().getId();
        long toId = route.getToStop().getId();
        List<Stop> stops = tripLogic.stops(appUserId, tripId);
        Stop from = stops.stream().filter(stop -> stop.getId() == fromId).findFirst().orElseThrow();
        Stop to = stops.stream().filter(stop -> stop.getId() == toId).findFirst().orElseThrow();
        return new Ends(from, to, stops);
    }

    /**
     * A night at the hotel: a stop there (named after the area) right before the target stop, arriving as proposed and
     * leaving at {@link #STOPOVER_DEPARTURE} on the day of check-out. The arrival at the target stop moves by the

     * nights on the way; accommodations there that start earlier go on the to-do list (tell them, or cancel the first
     * night). The way to the hotel is planned anew, the points of a detour are not taken over.
     */
    private Accepted stopover(long appUserId, long runId, int rank, long tripId, Ends ends, AdvisorProposal proposal) {
        ProposalStay stay = stay(proposal);
        Stop target = ends.to();
        ZoneOffset offset = offset(ends);
        OffsetDateTime arrival = proposal.getArrivalAt() == null ? null
                : proposal.getArrivalAt().withOffsetSameInstant(offset);
        OffsetDateTime departure = OffsetDateTime.of(stay.checkOut(), STOPOVER_DEPARTURE, offset);
        if (arrival != null && !departure.isAfter(arrival)) {
            arrival = null;
        }
        int position = ends.stops().indexOf(target);
        List<Long> planned = List.copyOf(tripEditing.addStop(appUserId, tripId,
                new NewStop(stay.place(), stay.latitude(), stay.longitude(), arrival, departure), position).keySet());
        long stayStopId = tripLogic.stops(appUserId, tripId).get(position).getId();

        long nights = ChronoUnit.DAYS.between(stay.checkIn(), stay.checkOut());
        if (target.getArrivalAt() != null && nights > 0) {
            OffsetDateTime shifted = target.getArrivalAt().plusDays(nights);
            // not before leaving the hotel; planning the route shows the real arrival
            OffsetDateTime later = shifted.isBefore(departure) ? departure : shifted;
            if (target.getDepartureAt() == null || !later.isAfter(target.getDepartureAt())) {

                tripEditing.updateStop(appUserId, tripId, target.getId(), new NewStop(target.getName(),
                        target.getLatitude(), target.getLongitude(), later, target.getDepartureAt()));
            }
        }
        int added = todos.lateArrival(appUserId, target.getId(), stay.checkOut()).size();
        log.info("Stopover of run {} taken: stop {} before stop {}", runId, stayStopId, target.getId());
        return new Accepted(runId, rank, ProposalKind.STOPOVER, tripId, planned, stayStopId, null, added);
    }

    /** Local offset of the route: of the target stop's arrival, else of the departure at the start. */
    private ZoneOffset offset(Ends ends) {
        if (ends.to().getArrivalAt() != null) {
            return ends.to().getArrivalAt().getOffset();
        }
        if (ends.from().getDepartureAt() != null) {
            return ends.from().getDepartureAt().getOffset();
        }
        return OffsetDateTime.now(clock).getOffset();
    }
    private static ProposalStay stay(AdvisorProposal proposal) {
        if (proposal.getStay() == null) {
            throw new ProposalNotApplicableException("proposal has no accommodation");
        }
        return proposal.getStay();
    }

    private long routeOf(long runId) {
        return Objects.requireNonNull(transactionTemplate.execute(_ -> runRepository.findById(runId)
                .map(AdvisorRun::getRouteId).orElseThrow(() -> new TripItemNotFoundException("run", runId))));
    }
}
