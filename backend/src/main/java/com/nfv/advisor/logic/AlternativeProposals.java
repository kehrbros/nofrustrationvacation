package com.nfv.advisor.logic;

import com.nfv.advisor.config.AdvisorProperties;
import com.nfv.advisor.data.AdvisorRunRepository;
import com.nfv.advisor.entities.AdvisorRun;
import com.nfv.advisor.entities.ProposalGoal;
import com.nfv.advisor.entities.RunStatus;
import com.nfv.advisor.entities.RunTrigger;
import com.nfv.incidents.entities.Category;
import com.nfv.incidents.logic.ActiveIncidents;
import com.nfv.incidents.logic.RouteConsequence;
import com.nfv.incidents.logic.RouteTooLate;
import com.nfv.trips.entities.Route;
import com.nfv.trips.logic.RouteSnapshots;
import com.nfv.trips.logic.TripLogic;

import com.nfv.trips.logic.TripItemNotFoundException;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Starts runs of the agent and reads their results, see Docs/advisor.md#api. Checks the ownership of the route, except
 * for automatic runs.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlternativeProposals {
    private static final Set<RunStatus> UNFINISHED = Set.of(RunStatus.PENDING, RunStatus.RUNNING);
    private static final String ABANDONED = "ABANDONED";

    /** Worst first: too late before delayed, sure hits before those within the tolerance, then the longer delay. */
    private static final Comparator<RouteConsequence> WORST_FIRST = Comparator
            .comparing((RouteConsequence c) -> c.category() == Category.TOO_LATE ? 0 : 1)
            .thenComparing(c -> c.counts() ? 0 : 1)
            .thenComparing(c -> c.delay() == null ? Long.MIN_VALUE : -c.delay().toSeconds())
            .thenComparingLong(RouteConsequence::id);

    private final RouteSnapshots routeSnapshots;
    private final TripLogic tripLogic;

    private final ActiveIncidents activeIncidents;
    private final RouteSituations situations;
    private final AlternativeAgent agent;
    private final AdvisorRunRepository runRepository;
    private final AdvisorProperties properties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * Starts a run in the background. If one is already running for the route, its id comes back.
     *
     * @param consequenceId the consequence to look at, {@code null}: the worst on the route
     * @param goal          {@code null}: {@code ARRIVE_ON_TIME}
     * @return id of the run
     * @throws TripItemNotFoundException      route unknown or of another user
     * @throws InvalidProposalInputException  the consequence is not on the route
     * @throws NoActiveConsequenceException   no current {@code TOO_LATE} or {@code DELAYED} consequence
     */
    public long start(long appUserId, long routeId, @Nullable Long consequenceId, @Nullable ProposalGoal goal) {
        return start(appUserId, routeId, consequenceId, null, goal);
    }

    /**
     * Like {@link #start(long, long, Long, ProposalGoal)}, the consequence also given by its incident (the app knows
     * incidents, consequences are replaced by every assessment).
     *
     * @param incidentId {@code null}: any incident of the route
     */
    public long start(long appUserId, long routeId, @Nullable Long consequenceId, @Nullable Long incidentId,
                      @Nullable ProposalGoal goal) {
        routeSnapshots.of(appUserId, routeId);
        RouteConsequence consequence = chosen(routeId, consequenceId, incidentId);
        String situation = situations.of(routeId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        record Started(long runId, boolean created) {
        }
        Started started = Objects.requireNonNull(transactionTemplate.execute(_ -> {
            Optional<AdvisorRun> running = runRepository.findByRouteIdAndStatusIn(routeId, UNFINISHED).stream()
                    .filter(run -> !expired(run, now)).findFirst();
            return running.map(run -> new Started(run.getId(), false))
                    .orElseGet(() -> new Started(runRepository.save(new AdvisorRun(routeId, consequence.id(),
                            consequence.incidentId(), RunTrigger.USER, goal == null ? ProposalGoal.ARRIVE_ON_TIME : goal, situation, null, now))
                            .getId(), true));
        }));
        if (started.created()) {
            Thread.ofVirtual().name("advisor-run").start(() -> agent.execute(started.runId(), appUserId));
        }
        return started.runId();
    }

    /**
     * Runs the agent for a route that is too late, see Docs/advisor.md#automatisch-nur-too_late. Nobody is logged in,
     * so no ownership check. Blocks until the run has ended: call it on a thread of its own.
     *
     * @return id of the run, empty if there is nothing to do: the situation was handled already, or the route is no
     * longer too late or gone
     * @throws TripItemNotFoundException the route was deleted just now
     */
    public Optional<Long> startAutomatic(RouteTooLate event) {
        long routeId = event.routeId();
        OffsetDateTime now = OffsetDateTime.now(clock);
        // on its own: an abandoned run has to give up its key before the check and the insert below
        transactionTemplate.executeWithoutResult(_ -> runRepository.findByRouteIdAndStatusIn(routeId, UNFINISHED)
                .forEach(run -> expired(run, now)));
        String key = situations.autoKey(event);
        if (Boolean.TRUE.equals(transactionTemplate.execute(_ -> runRepository.existsByRouteIdAndAutoKey(routeId, key)))) {
            return Optional.empty();
        }
        // the event may be out of date by now
        Optional<RouteConsequence> worst = activeIncidents.consequencesOnRoute(routeId).stream()
                .filter(c -> !c.over() && c.category() == Category.TOO_LATE).min(WORST_FIRST);
        if (worst.isEmpty()) {
            return Optional.empty();
        }
        long appUserId = routeSnapshots.ownerOf(routeId);
        String situation = situations.of(routeId);
        long runId;
        try {
            runId = Objects.requireNonNull(transactionTemplate.execute(_ -> runRepository.save(new AdvisorRun(routeId,
                    worst.get().id(), worst.get().incidentId(), RunTrigger.AUTO, ProposalGoal.ARRIVE_ON_TIME, situation, key, now)).getId()));
        } catch (DataIntegrityViolationException e) {
            // another event got there first, or route or consequence are gone by now
            log.debug("No automatic advisor run for route {}: {}", routeId, e.getMessage());
            return Optional.empty();
        }
        agent.execute(runId, appUserId);
        return Optional.of(runId);
    }

    /** The run, also one started automatically. */
    public ProposalRun get(long appUserId, long runId) {
        long routeId = Objects.requireNonNull(transactionTemplate.execute(_ -> runRepository.findById(runId)
                .map(AdvisorRun::getRouteId).orElseThrow(() -> new TripItemNotFoundException("run", runId))));
        routeSnapshots.of(appUserId, routeId);
        return read(routeId, runId);
    }

    /** The last run of the route, empty if there is none. */
    public Optional<ProposalRun> latest(long appUserId, long routeId) {
        routeSnapshots.of(appUserId, routeId);
        return transactionTemplate.execute(_ -> runRepository.findFirstByRouteIdOrderByIdDesc(routeId)
                .map(AdvisorRun::getId)).map(runId -> read(routeId, runId));
    }

    /** The last run of every route of the trip that has one, in the order of the routes. */
    public List<ProposalRun> latestOfTrip(long appUserId, long tripId) {
        List<Long> routeIds = tripLogic.routes(appUserId, tripId).stream().map(Route::getId).toList();
        return routeIds.stream()
                .flatMap(routeId -> transactionTemplate.execute(_ -> runRepository.findFirstByRouteIdOrderByIdDesc(routeId)
                        .map(AdvisorRun::getId)).map(runId -> read(routeId, runId)).stream())
                .toList();
    }

    /** A restart ends what was running, otherwise those runs would keep the key of their situation. */
    @EventListener(ApplicationReadyEvent.class)
    public void endInterruptedRuns() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        transactionTemplate.executeWithoutResult(_ -> runRepository.findByStatusIn(UNFINISHED)
                .forEach(run -> run.failed("INTERRUPTED", now)));
    }

    private ProposalRun read(long routeId, long runId) {
        String situation = situations.of(routeId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        return transactionTemplate.execute(_ -> {
            AdvisorRun run = runRepository.findById(runId).orElseThrow();
            expired(run, now);
            return ProposalRun.of(run, !situation.equals(run.getSituation()));
        });
    }

    private boolean expired(AdvisorRun run, OffsetDateTime now) {
        if (run.isAbandoned(now, properties.runTimeout())) {
            run.failed(ABANDONED, now);
            return true;
        }
        return false;
    }

    private RouteConsequence chosen(long routeId, @Nullable Long consequenceId, @Nullable Long incidentId) {
        List<RouteConsequence> all = activeIncidents.consequencesOnRoute(routeId);
        if (consequenceId != null && all.stream().noneMatch(c -> c.id() == consequenceId)) {
            throw new InvalidProposalInputException("consequence " + consequenceId + " is not on the route");
        }
        if (incidentId != null && all.stream().noneMatch(c -> c.incidentId() == incidentId)) {
            throw new InvalidProposalInputException("incident " + incidentId + " is not on the route");
        }
        return all.stream()
                .filter(c -> consequenceId == null || c.id() == consequenceId)
                .filter(c -> incidentId == null || c.incidentId() == incidentId)
                .filter(c -> !c.over() && (c.category() == Category.TOO_LATE || c.category() == Category.DELAYED))
                .min(WORST_FIRST)
                .orElseThrow(() -> new NoActiveConsequenceException(routeId));
    }
}
