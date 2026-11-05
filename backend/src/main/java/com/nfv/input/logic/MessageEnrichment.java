package com.nfv.input.logic;

import com.nfv.incidents.entities.VehicleScope;
import com.nfv.incidents.logic.TimeWindow;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Values from the free text of an event that {@code read} cannot get, checked strictly before use, see
 * Docs/traffic-feed.md#anreicherung-messageenrichment. So far only the time windows:
 * <ul>
 *     <li>local time of the region → points in time with the zone of the source (in code, not in the model)</li>
 *     <li>end after start, at most 24 h each, at most 200 windows; otherwise the whole answer is rejected</li>
 *     <li>windows are cut to the validity {@code validFrom}–{@code validTo} of the source, never extend it; windows
 *     entirely outside are dropped, if none is left the answer is rejected</li>
 * </ul>
 */
@Component
public class MessageEnrichment {
    static final Duration MAX_WINDOW = Duration.ofHours(24);
    static final int MAX_WINDOWS = 200;
    private static final Logger LOG = LoggerFactory.getLogger(MessageEnrichment.class);

    private final EnrichmentModel model;

    public MessageEnrichment(EnrichmentModel model) {
        this.model = model;
    }

    /**
     * Checked result of the enrichment.
     *
     * @param windows      empty: applies throughout
     * @param vehicleScope {@code null}: not stated (all vehicles)
     */
    public record Enrichment(List<TimeWindow> windows, @Nullable VehicleScope vehicleScope) {
    }

    /** @return empty if there is no usable answer (no model, no text, windows rejected) */
    public Optional<Enrichment> enrich(TrafficSource source, TrafficReport report) {
        if (report.messageText() == null || report.messageText().isBlank()) {
            return Optional.empty();
        }
        return model.extract(report.messageText(), report)
                .flatMap(answer -> check(answer.timeWindows(), source.zone(), report.validFrom(), report.validTo())
                        .map(windows -> new Enrichment(windows, answer.vehicleScope())));
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
