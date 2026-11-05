package com.nfv.input.logic;

import com.nfv.incidents.entities.VehicleScope;
import jakarta.annotation.Nullable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The model call of {@link MessageEnrichment}: reads the free text of an event. Answers are unchecked, the checks are
 * in {@link MessageEnrichment}. The call via {@code assistant.StructuredExtraction} (system quota, stored results per
 * text hash) follows with stage 4; until then {@link NoEnrichmentModel} answers nothing, see
 * Docs/traffic-feed.md#anreicherung-messageenrichment.
 */
public interface EnrichmentModel {
    /**
     * @param messageText all free texts of the event
     * @param report      what {@code read} found, as context (validity, type)
     * @return empty if there is no answer (no model, quota exhausted, failure)
     */
    Optional<Answer> extract(String messageText, TrafficReport report);

    /**
     * @param timeWindows  windows in local time of the region with date, empty: applies throughout
     * @param vehicleScope vehicles the event applies to, {@code null}: not stated (all)
     */
    record Answer(List<LocalWindow> timeWindows, @Nullable VehicleScope vehicleScope) {
        public Answer {
            timeWindows = List.copyOf(timeWindows);
        }

        public Answer(List<LocalWindow> timeWindows) {
            this(timeWindows, null);
        }
    }

    record LocalWindow(LocalDateTime start, LocalDateTime end) {
    }
}
