package com.nfv.input.logic;

import com.nfv.incidents.entities.IncidentType;
import com.nfv.incidents.logic.IncidentReporting;
import com.nfv.incidents.logic.ReportOutcome;
import com.nfv.incidents.logic.ReportedIncident;
import com.nfv.input.config.InputProperties;
import com.nfv.input.data.EventRepository;
import com.nfv.input.entities.Event;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Turns stored traffic events into incidents, see Docs/traffic-feed.md#verarbeitung-trafficeventprocessor. Runs after
 * the commit of a poll on a virtual thread, one transaction per event, oldest first:
 * <ol>
 *     <li>{@link TrafficSource#read} → {@link TrafficReport}</li>
 *     <li>{@link MessageEnrichment}: time windows and vehicle scope from the text, checked</li>
 *     <li>{@link IncidentReporting#report} with the incident of the previous version of the same event</li>
 *     <li>{@code processed(incidentId)}, or {@code processed(null)} with the reason as {@code processing_note};
 *     {@code ROUTING_UNAVAILABLE} leaves the event pending for the next run</li>
 * </ol>
 * Without a usable enrichment, events with times of day in their text become {@code OTHER} (no closure), so a
 * closure at night does not block the whole day. Closures among them are marked {@code timesUnconfirmed}, so they are
 * assessed as {@code DELAYED} with "check times" rather than {@code NO_IMPACT}.
 */
@Component
public class TrafficEventProcessor {
    private static final Logger LOG = LoggerFactory.getLogger(TrafficEventProcessor.class);
    /** Times of day in English texts: "9pm", "4:30am", "21:00", "nights", "overnight". */
    private static final Pattern TIMES_OF_DAY = Pattern.compile(
            "(?i)\\b\\d{1,2}(:\\d{2})?\\s?(am|pm)\\b|\\b\\d{1,2}:\\d{2}\\b|\\bnights?\\b|\\bovernight\\b");
    static final String TIMES_IN_TEXT = "TIMES_IN_TEXT: reported as OTHER without time windows from the enrichment";

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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterPoll(FeedDiff.SourcePolled polled) {
        if (properties.processing().afterPoll()) {
            Thread.ofVirtual().name("traffic-processing").start(this::processPending);
        }
    }

    /** Processes all pending traffic events, oldest first. Runs one at a time. */
    public synchronized Result processPending() {
        int processed = 0;
        int withIncident = 0;
        int pending = 0;
        for (long eventId : eventRepository.findPendingTrafficEventIds()) {
            Optional<Long> outcome;
            try {
                outcome = transactionTemplate.execute(_ -> process(eventId));
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
        return new Result(processed, withIncident, pending);
    }

    /** @return incident of the event, empty if none, {@code null} if the event stays pending */
    private @Nullable Optional<Long> process(long eventId) {
        Event event = eventRepository.findById(eventId).orElse(null);
        if (event == null || event.getProcessedAt() != null) {
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
        Optional<MessageEnrichment.Enrichment> enriched = ended ? Optional.empty()
                : enrichment.enrich(source.get(), report);
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
                report.validTo(), report.delay(), report.assumedDelay(), report.location(), report.roadRef(),
                report.direction(), source.get().networkRegion(), ended,
                enriched.map(MessageEnrichment.Enrichment::windows).orElse(report.windows()),
                enriched.map(MessageEnrichment.Enrichment::vehicleScope).orElse(report.vehicleScope()),
                timesUnconfirmed, report.linkOnly()), previous);
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
