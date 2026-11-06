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
import com.nfv.trips.logic.RouteSnapshots;
import com.nfv.trips.logic.TripItemNotFoundException;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Starts runs of the agent and reads their results, see Docs/advisor.md#api. Checks the ownership of the route. */
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
        routeSnapshots.of(appUserId, routeId);
        RouteConsequence consequence = chosen(routeId, consequenceId);
        String situation = situations.of(routeId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        record Started(long runId, boolean created) {
        }
        Started started = Objects.requireNonNull(transactionTemplate.execute(_ -> {
            Optional<AdvisorRun> running = runRepository.findByRouteIdAndStatusIn(routeId, UNFINISHED).stream()
                    .filter(run -> !expired(run, now)).findFirst();
            return running.map(run -> new Started(run.getId(), false))
                    .orElseGet(() -> new Started(runRepository.save(new AdvisorRun(routeId, consequence.id(),
                            RunTrigger.USER, goal == null ? ProposalGoal.ARRIVE_ON_TIME : goal, situation, null, now))
                            .getId(), true));
        }));
        if (started.created()) {
            Thread.ofVirtual().name("advisor-run").start(() -> agent.execute(started.runId(), appUserId));
        }
        return started.runId();
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

    private RouteConsequence chosen(long routeId, @Nullable Long consequenceId) {
        List<RouteConsequence> all = activeIncidents.consequencesOnRoute(routeId);
        if (consequenceId != null && all.stream().noneMatch(c -> c.id() == consequenceId)) {
            throw new InvalidProposalInputException("consequence " + consequenceId + " is not on the route");
        }
        return all.stream()
                .filter(c -> consequenceId == null || c.id() == consequenceId)
                .filter(c -> !c.over() && (c.category() == Category.TOO_LATE || c.category() == Category.DELAYED))
                .min(WORST_FIRST)
                .orElseThrow(() -> new NoActiveConsequenceException(routeId));
    }
}
