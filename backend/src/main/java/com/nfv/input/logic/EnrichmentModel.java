package com.nfv.input.logic;

import com.nfv.incidents.entities.VehicleScope;
import com.nfv.incidents.logic.TravelDirection;
import jakarta.annotation.Nullable;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * The model call of {@link MessageEnrichment}: reads the free text of an event, see
 * Docs/traffic-feed.md#anreicherung-messageenrichment. Answers are unchecked, the checks are in
 * {@link MessageEnrichment}.
 */
public interface EnrichmentModel {
    /**
     * @param messageText all free texts of the event
     * @param report      what {@code read} found, as context (validity, type)
     * @param zone        time zone of the region, the answer is in its local time
     * @return empty if there is no model (no key, switched off)
     * @throws EnrichmentDeferredException no answer now, worth trying again later (quota used up, model not reachable)
     */
    Optional<Answer> extract(String messageText, TrafficReport report, ZoneId zone);

    /** Name of the model, stored with its answers; {@code null} if unknown. */
    default @Nullable String modelName() {
        return null;
    }

    enum Confidence {
        HIGH, MEDIUM, LOW
    }

    /**
     * @param timeWindows         windows in local time of the region with date, empty: applies throughout
     * @param vehicleScope        vehicles the event applies to, {@code null}: not stated (all)
     * @param closedDuringWindows the text says the road is closed, also if the source does not
     * @param expectedReopening   when the text expects the road to reopen, local time, {@code null}: not stated
     * @param delayMinutes        delay the text names as a figure, {@code null}: none
     * @param direction           direction the text limits the event to, {@code null}: not stated
     * @param evidence            literal part of the text the answer rests on, {@code null} only in tests
     */
    record Answer(List<LocalWindow> timeWindows, @Nullable VehicleScope vehicleScope, boolean closedDuringWindows,
                  @Nullable LocalDateTime expectedReopening, @Nullable Integer delayMinutes,
                  @Nullable TravelDirection direction, @Nullable String evidence, Confidence confidence) {
        public Answer {
            timeWindows = List.copyOf(timeWindows);
        }

        /** Only windows, with high confidence. */
        public Answer(List<LocalWindow> timeWindows) {
            this(timeWindows, null, false, null, null, null, null, Confidence.HIGH);
        }
    }

    record LocalWindow(LocalDateTime start, LocalDateTime end) {
    }
}
