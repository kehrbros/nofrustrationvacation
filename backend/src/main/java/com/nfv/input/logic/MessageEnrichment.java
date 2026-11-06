package com.nfv.input.logic;

import com.nfv.incidents.entities.VehicleScope;
import com.nfv.incidents.logic.TimeWindow;
import com.nfv.incidents.logic.TravelDirection;
import com.nfv.input.data.StoredEnrichmentRepository;
import com.nfv.input.entities.EventSource;
import com.nfv.input.entities.StoredEnrichment;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Values from the free text of an event that {@code read} cannot get, see
 * Docs/traffic-feed.md#anreicherung-messageenrichment. The model is asked once per text and validity ({@link #ask},
 * outside a transaction, the call can take long); its answer is stored as it came and checked strictly on every use
 * ({@link #enrichment}):
 * <ul>
 *     <li>low confidence, or evidence that is not in the text: the whole answer is rejected</li>
 *     <li>local time of the region → points in time with the zone of the source (in code, not in the model)</li>
 *     <li>windows: end after start, at most 24 h each, at most 200; otherwise the whole answer is rejected. Cut to the
 *     validity {@code validFrom}–{@code validTo}, never extending it; if none is left the answer is rejected</li>
 *     <li>closed: only with high confidence</li>
 *     <li>reopening: only without an end from the source, after the start, at most 30 days; otherwise ignored</li>
 *     <li>delay: only without a figure from the source, 0–720 minutes; otherwise ignored</li>
 *     <li>direction: only narrows, the direction of the source goes first</li>
 * </ul>
 */
@Component
public class MessageEnrichment {
    static final Duration MAX_WINDOW = Duration.ofHours(24);
    static final int MAX_WINDOWS = 200;
    static final Duration MAX_REOPENING = Duration.ofDays(30);
    static final int MAX_DELAY_MINUTES = 720;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Logger LOG = LoggerFactory.getLogger(MessageEnrichment.class);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final EnrichmentModel model;
    private final StoredEnrichmentRepository repository;
    private final Clock clock;

    public MessageEnrichment(EnrichmentModel model, StoredEnrichmentRepository repository, Clock clock) {
        this.model = model;
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Checked result of the enrichment.
     *
     * @param windows      empty: applies throughout
     * @param vehicleScope {@code null}: not stated (all vehicles)
     * @param closed       the text says the road is closed
     * @param validTo      end of the event, from the source or expected by the text
     * @param delay        delay named by the text, an estimate; {@code null}: none
     * @param direction    {@code null}: not narrowed
     */
    public record Enrichment(List<TimeWindow> windows, @Nullable VehicleScope vehicleScope, boolean closed,
                             @Nullable OffsetDateTime validTo, @Nullable Duration delay,
                             @Nullable TravelDirection direction) {
    }

    /** Whether the text of the event needs a model at all: not ended, has text, no windows from the source. */
    static boolean wantsAnswer(TrafficReport report, boolean ended) {
        return !ended && report.windows().isEmpty() && SourceItem.hash(report.messageText()) != null;
    }

    public boolean hasAnswer(EventSource source, String subjectId, TrafficReport report) {
        String hash = SourceItem.hash(report.messageText());
        return hash != null && repository.findBySourceIdAndSubjectIdAndMessageHashAndValidity(source.getId(), subjectId,
                hash, validity(report)).isPresent();
    }

    /** The validity as the model is given it: its dated windows depend on it, so a new validity asks again. */
    private static String validity(TrafficReport report) {
        return report.validFrom().toInstant() + "/" + (report.validTo() == null ? "open" : report.validTo().toInstant());
    }

    /**
     * Asks the model and stores its answer. Not in a transaction: the call can take long.
     *
     * @return whether there was an answer, {@code false} without a model
     * @throws EnrichmentDeferredException no answer now, try again later
     */
    public boolean ask(EventSource source, String subjectId, TrafficReport report, ZoneId zone) {
        Optional<EnrichmentModel.Answer> answer = model.extract(report.messageText(), report, zone);
        if (answer.isEmpty()) {
            return false;
        }
        repository.save(new StoredEnrichment(source, subjectId, SourceItem.hash(report.messageText()), validity(report),
                MAPPER.writeValueAsString(answer.get()), model.modelName(), OffsetDateTime.now(clock)));
        return true;
    }

    /** @return the checked stored answer for the text of the event, empty if there is none or it is rejected */
    public Optional<Enrichment> enrichment(EventSource source, String subjectId, TrafficReport report, ZoneId zone) {
        String hash = SourceItem.hash(report.messageText());
        if (hash == null) {
            return Optional.empty();
        }
        return repository.findBySourceIdAndSubjectIdAndMessageHashAndValidity(source.getId(), subjectId, hash,
                        validity(report))
                .flatMap(stored -> read(stored.getResult()))
                .flatMap(answer -> check(answer, report, zone));
    }

    private static Optional<EnrichmentModel.Answer> read(String result) {
        try {
            return Optional.of(MAPPER.readValue(result, EnrichmentModel.Answer.class));
        } catch (JacksonException e) {
            LOG.warn("Stored enrichment unreadable: {}", e.getOriginalMessage());
            return Optional.empty();
        }
    }

    static Optional<Enrichment> check(EnrichmentModel.Answer answer, TrafficReport report, ZoneId zone) {
        if (answer.confidence() == EnrichmentModel.Confidence.LOW) {
            LOG.debug("Enrichment rejected: low confidence");
            return Optional.empty();
        }
        if (answer.evidence() != null && !normalized(report.messageText()).contains(normalized(answer.evidence()))) {
            LOG.warn("Enrichment rejected: evidence is not in the text: {}", answer.evidence());
            return Optional.empty();
        }
        OffsetDateTime validTo = validTo(answer, report, zone);
        return check(answer.timeWindows(), zone, report.validFrom(), validTo).map(windows -> new Enrichment(windows,
                answer.vehicleScope(),
                answer.closedDuringWindows() && answer.confidence() == EnrichmentModel.Confidence.HIGH, validTo,
                delay(answer, report), direction(answer, report)));
    }

    private static @Nullable OffsetDateTime validTo(EnrichmentModel.Answer answer, TrafficReport report, ZoneId zone) {
        if (report.validTo() != null || answer.expectedReopening() == null) {
            return report.validTo();
        }
        OffsetDateTime reopening = answer.expectedReopening().atZone(zone).toOffsetDateTime();
        if (!reopening.isAfter(report.validFrom()) || reopening.isAfter(report.validFrom().plus(MAX_REOPENING))) {
            LOG.warn("Enrichment: ignoring expected reopening {}", answer.expectedReopening());
            return null;
        }
        return reopening;
    }

    private static @Nullable Duration delay(EnrichmentModel.Answer answer, TrafficReport report) {
        Integer minutes = answer.delayMinutes();
        if (report.delay() != null || minutes == null) {
            return null;
        }
        if (minutes < 0 || minutes > MAX_DELAY_MINUTES) {
            LOG.warn("Enrichment: ignoring delay of {} minutes", minutes);
            return null;
        }
        return Duration.ofMinutes(minutes);
    }

    private static @Nullable TravelDirection direction(EnrichmentModel.Answer answer, TrafficReport report) {
        boolean sourceIsOpen = report.direction() == null || report.direction() == TravelDirection.BOTH;
        return sourceIsOpen && answer.direction() != TravelDirection.BOTH ? answer.direction() : null;
    }

    private static String normalized(@Nullable String text) {
        return text == null ? "" : WHITESPACE.matcher(text.strip()).replaceAll(" ");
    }

    static Optional<List<TimeWindow>> check(List<EnrichmentModel.LocalWindow> windows, ZoneId zone,
                                            OffsetDateTime validFrom, @Nullable OffsetDateTime validTo) {
        if (windows.isEmpty()) {
            return Optional.of(List.of());
        }
        if (windows.size() > MAX_WINDOWS) {
            LOG.warn("Enrichment rejected: {} time windows, at most {}", windows.size(), MAX_WINDOWS);
            return Optional.empty();
        }
        List<TimeWindow> checked = new ArrayList<>();
        for (EnrichmentModel.LocalWindow window : windows) {
            OffsetDateTime start = window.start().atZone(zone).toOffsetDateTime();
            OffsetDateTime end = window.end().atZone(zone).toOffsetDateTime();
            if (!end.isAfter(start) || Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
                LOG.warn("Enrichment rejected: invalid time window {}–{}", window.start(), window.end());
                return Optional.empty();
            }
            OffsetDateTime from = start.isBefore(validFrom) ? validFrom : start;
            OffsetDateTime to = validTo != null && end.isAfter(validTo) ? validTo : end;
            if (to.isAfter(from)) {
                checked.add(new TimeWindow(from, to));
            }
        }
        if (checked.isEmpty()) {
            LOG.warn("Enrichment rejected: all {} time windows outside the validity", windows.size());
            return Optional.empty();
        }
        checked.sort(Comparator.comparing(TimeWindow::startsAt));
        return Optional.of(List.copyOf(checked));
    }
}
