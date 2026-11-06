package com.nfv.input.logic;

import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.logic.IncidentReporting;
import com.nfv.incidents.logic.ReportOutcome;
import com.nfv.incidents.logic.ReportedIncident;
import com.nfv.input.config.InputProperties;
import com.nfv.input.data.EventRepository;
import com.nfv.input.entities.Event;
import com.nfv.input.entities.EventSource;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Turns stored traffic events into incidents, see Docs/traffic-feed.md#verarbeitung-trafficeventprocessor. Runs after
 * the commit of a poll on a virtual thread, one transaction per event, oldest first:
 * <ol>
 *     <li>the model reads the text if it has not read this text yet, before the transaction: the call can take long</li>
 *     <li>{@link TrafficSource#read} → {@link TrafficReport}</li>
 *     <li>{@link MessageEnrichment}: what the text says, checked</li>
 *     <li>{@link IncidentReporting#report} with the incident of the previous version of the same event</li>
 *     <li>{@code processed(incidentId)}, or {@code processed(null)} with the reason as {@code processing_note};
 *     {@code ROUTING_UNAVAILABLE} leaves the event pending for the next run</li>
 * </ol>
 * Without a usable enrichment, events with times of day in their text become {@code OTHER} (no closure), so a
 * closure at night does not block the whole day. Closures among them are marked {@code timesUnconfirmed}, so they are
 * assessed as {@code DELAYED} with "check times" rather than {@code NO_IMPACT}.
 * <p>
 * If the model gives no answer now (quota used up, not reachable) the event is processed without and marked
 * {@code enrichment_pending}. Every run catches up as far as the model answers and changes the incident.
 */
@Component
public class TrafficEventProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(TrafficEventProcessor.class);
    /** Times of day in English texts: "9pm", "4:30am", "21:00", "nights", "overnight". */
    private static final Pattern TIMES_OF_DAY = Pattern.compile(
            "(?i)\\b\\d{1,2}(:\\d{2})?\\s?(am|pm)\\b|\\b\\d{1,2}:\\d{2}\\b|\\bnights?\\b|\\bovernight\\b");
    static final String TIMES_IN_TEXT = "TIMES_IN_TEXT: reported as OTHER without time windows from the enrichment";
    /** Types the text may raise to a closure; an accident stays one. */
    private static final Set<IncidentType> MAY_BECOME_CLOSURE = EnumSet.of(IncidentType.OTHER, IncidentType.ROADWORKS,
            IncidentType.CONGESTION);
    /** Tries to catch up per event: a text the model cannot answer must not cost quota forever. */
    static final int MAX_ENRICHMENT_ATTEMPTS = 20;

    private final TrafficSources sources;
    private final EventRepository eventRepository;
    private final IncidentReporting incidentReporting;
    private final MessageEnrichment enrichment;
    private final TransactionTemplate transactionTemplate;
    private final InputProperties properties;
    private final Clock clock;

    public TrafficEventProcessor(TrafficSources sources, EventRepository eventRepository,
                                 IncidentReporting incidentReporting, MessageEnrichment enrichment,
                                 TransactionTemplate transactionTemplate, InputProperties properties, Clock clock) {
        this.sources = sources;
        this.eventRepository = eventRepository;
        this.incidentReporting = incidentReporting;
        this.enrichment = enrichment;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /** Result of one run: events processed, of them with an incident, still pending. */
    public record Result(int processed, int withIncident, int pending) {
    }

    /** What the model needs for one event. */
    private record Question(TrafficSource source, EventSource eventSource, String subjectId, TrafficReport report) {
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterPoll(FeedDiff.SourcePolled polled) {
        if (properties.processing().afterPoll()) {
            Thread.ofVirtual().name("traffic-processing").start(this::processPending);
        }
    }

    /** Processes all pending traffic events, oldest first, then catches up enrichments. Runs one at a time. */
    public synchronized Result processPending() {
        int processed = 0;
        int withIncident = 0;
        int pending = 0;
        // after the first failure the model is left alone until the next run
        boolean modelAnswers = true;
        for (long eventId : eventRepository.findPendingTrafficEventIds()) {
            Optional<Long> outcome;
            try {
                Optional<Question> question = transactionTemplate.execute(_ -> question(eventId));
                boolean deferred = false;
                if (question.isPresent() && !hasAnswer(question.get())) {
                    if (modelAnswers) {
                        try {
                            ask(question.get());
                        } catch (EnrichmentDeferredException e) {
                            LOG.info("Enrichment deferred: {}", e.getMessage());
                            modelAnswers = false;
                            deferred = true;
                        }
                    } else {
                        deferred = true;
                    }
                }
                boolean enrichmentDeferred = deferred;
                outcome = transactionTemplate.execute(_ -> process(eventId, enrichmentDeferred, false));
            } catch (RuntimeException e) {
                LOG.error("Processing traffic event {} failed", eventId, e);
                transactionTemplate.executeWithoutResult(_ -> eventRepository.findById(eventId).ifPresent(event ->
                        event.processed(null, "FAILED: " + e.getMessage(), OffsetDateTime.now(clock))));
                processed++;
                continue;
            }
            if (outcome == null) {
                pending++;
            } else {
                processed++;
                if (outcome.isPresent()) {
                    withIncident++;
                }
            }
        }
        if (processed > 0 || pending > 0) {
            LOG.info("Traffic events: {} processed ({} with incident), {} pending", processed, withIncident, pending);
        }
        if (modelAnswers) {
            catchUpEnrichments();
        }
        return new Result(processed, withIncident, pending);
    }

    /** Asks for events still without an answer, least tried first, and changes their incidents. */
    private void catchUpEnrichments() {
        int caughtUp = 0;
        for (long eventId : eventRepository.findEnrichmentPendingIds()) {
            Optional<Question> question = transactionTemplate.execute(_ -> {
                Event event = eventRepository.findById(eventId).orElse(null);
                Optional<Question> current = event == null || isSuperseded(event) ? Optional.empty() : question(eventId);
                if (event != null && current.isEmpty()) {
                    event.enrichmentSettled();
                }
                return current;
            });
            if (question.isEmpty()) {
                continue;
            }
            try {
                if (!hasAnswer(question.get()) && !ask(question.get())) {
                    settle(eventId);
                    continue;
                }
            } catch (EnrichmentDeferredException e) {
                LOG.info("Enrichment still deferred: {}", e.getMessage());
                if (!e.isQuotaUsedUp()) {
                    transactionTemplate.executeWithoutResult(_ -> eventRepository.findById(eventId).ifPresent(event ->
                            event.enrichmentAttemptFailed(MAX_ENRICHMENT_ATTEMPTS)));
                }
                break;
            }
            try {
                transactionTemplate.execute(_ -> process(eventId, false, true));
                caughtUp++;
            } catch (RuntimeException e) {
                LOG.error("Catching up the enrichment of traffic event {} failed", eventId, e);
                settle(eventId);
            }
        }
        if (caughtUp > 0) {
            LOG.info("Traffic events: caught up {} enrichments", caughtUp);
        }
    }

    private void settle(long eventId) {
        transactionTemplate.executeWithoutResult(_ -> eventRepository.findById(eventId)
                .ifPresent(Event::enrichmentSettled));
    }

    private boolean isSuperseded(Event event) {
        return event.getSubjectId() != null && eventRepository
                .findFirstBySourceIdAndSubjectIdOrderByIdDesc(event.getSource().getId(), event.getSubjectId())
                .filter(latest -> latest.getId() != event.getId()).isPresent();
    }

    private boolean hasAnswer(Question question) {
        return enrichment.hasAnswer(question.eventSource(), question.subjectId(), question.report());
    }

    private boolean ask(Question question) {
        return enrichment.ask(question.eventSource(), question.subjectId(), question.report(),
                question.source().zone());
    }

    /** @return what the model should read for the event, empty if nothing */
    private Optional<Question> question(long eventId) {
        Event event = eventRepository.findById(eventId).orElse(null);
        if (event == null || event.getSubjectId() == null) {
            return Optional.empty();
        }
        Optional<TrafficSource> source = sources.find(event.getSource().getName());
        if (source.isEmpty()) {
            return Optional.empty();
        }
        TrafficReport report;
        try {
            report = source.get().read(event.getPayload());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        boolean ended = report.resolved() || FeedDiff.isCleared(event);
        return MessageEnrichment.wantsAnswer(report, ended)
                ? Optional.of(new Question(source.get(), sources.eventSource(source.get()), event.getSubjectId(), report))
                : Optional.empty();
    }

    /**
     * @param enrichmentDeferred the model gave no answer, catch up later
     * @param again              catching up an event already processed
     * @return incident of the event, empty if none, {@code null} if the event stays pending
     */
    private @Nullable Optional<Long> process(long eventId, boolean enrichmentDeferred, boolean again) {
        Event event = eventRepository.findById(eventId).orElse(null);
        if (event == null || !again && event.getProcessedAt() != null) {
            return Optional.empty();
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<TrafficSource> source = sources.find(event.getSource().getName());
        if (source.isEmpty()) {
            event.processed(null, "UNKNOWN_SOURCE", now);
            return Optional.empty();
        }
        TrafficReport report;
        try {
            report = source.get().read(event.getPayload());
        } catch (IllegalArgumentException e) {
            event.processed(null, "UNREADABLE: " + e.getMessage(), now);
            return Optional.empty();
        }
        boolean ended = report.resolved() || FeedDiff.isCleared(event);
        IncidentType type = report.type();
        String note = null;
        boolean timesUnconfirmed = false;
        // ending never asks the model
        Optional<MessageEnrichment.Enrichment> enriched = MessageEnrichment.wantsAnswer(report, ended)
                && event.getSubjectId() != null
                ? enrichment.enrichment(event.getSource(), event.getSubjectId(), report, source.get().zone())
                : Optional.empty();
        if (enriched.filter(MessageEnrichment.Enrichment::closed).isPresent() && MAY_BECOME_CLOSURE.contains(type)) {
            type = IncidentType.CLOSURE;
        }
        if (!ended && enriched.isEmpty() && report.windows().isEmpty() && type != IncidentType.OTHER
                && mentionsTimesOfDay(report.messageText())) {
            timesUnconfirmed = type == IncidentType.CLOSURE;
            type = IncidentType.OTHER;
            note = TIMES_IN_TEXT;
        }
        Long previous = event.getSubjectId() == null ? null : eventRepository
                .findFirstBySourceIdAndSubjectIdAndIncidentIdIsNotNullOrderByIdDesc(event.getSource().getId(),
                        event.getSubjectId())
                .map(Event::getIncidentId).orElse(null);
        String description = Stream.of(report.title(), report.description())
                .filter(text -> text != null && !text.isBlank()).collect(Collectors.joining("\n"));
        ReportOutcome outcome = incidentReporting.report(new ReportedIncident(type, description, report.validFrom(),
                enriched.map(MessageEnrichment.Enrichment::validTo).orElse(report.validTo()), report.delay(),
                // a figure from the text is an estimate, shown like the assumption of a source
                enriched.map(MessageEnrichment.Enrichment::delay).orElse(report.assumedDelay()), report.location(),
                report.roadRef(), enriched.map(MessageEnrichment.Enrichment::direction).orElse(report.direction()),
                source.get().networkRegion(), ended,
                enriched.map(MessageEnrichment.Enrichment::windows).orElse(report.windows()),
                enriched.map(MessageEnrichment.Enrichment::vehicleScope).orElse(report.vehicleScope()),
                timesUnconfirmed, report.linkOnly()), previous);
        if (enrichmentDeferred) {
            event.enrichmentDeferred();
        } else if (again) {
            event.enrichmentSettled();
        }
        if (outcome.reason() == ReportOutcome.Reason.ROUTING_UNAVAILABLE) {
            return null;
        }
        event.processed(outcome.incidentId(), outcome.reason() == null ? note : outcome.reason().name(), now);
        return Optional.ofNullable(outcome.incidentId());
    }

    static boolean mentionsTimesOfDay(@Nullable String text) {
        return text != null && TIMES_OF_DAY.matcher(text).find();
    }
}
