package com.nfv.input.logic;

import com.nfv.incidents.logic.IncidentOverview;
import com.nfv.incidents.logic.IncidentReporting;
import com.nfv.input.data.EventRepository;
import com.nfv.input.data.EventSourceRepository;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/** What became of an event of a source, for diagnosis, see Docs/traffic-feed.md#test-source-manual. */
@Component
@RequiredArgsConstructor
public class TrafficEventInspection {
    /** No result yet: not processed, e.g. the network of the region is not loaded. */
    static final String PENDING = "PENDING";
    static final String INCIDENT = "INCIDENT";

    private final EventSourceRepository eventSourceRepository;
    private final EventRepository eventRepository;
    private final IncidentReporting incidentReporting;

    /**
     * @param outcome  {@code PENDING}, {@code INCIDENT} or the reason without incident, e.g. {@code NO_SEGMENTS}
     * @param note     note on the processing in full, e.g. {@code TIMES_IN_TEXT: …}
     * @param incident {@code null} if this version led to none
     */
    public record EventStatus(String source, String subjectId, String externalId, String outcome,
                              @Nullable String note, @Nullable OffsetDateTime processedAt,
                              @Nullable IncidentOverview incident) {
    }

    /** Latest version of the event, empty if the source or the event is unknown. */
    @Transactional(readOnly = true)
    public Optional<EventStatus> latest(String sourceName, String subjectId) {
        return eventSourceRepository.findByName(sourceName)
                .flatMap(source -> eventRepository.findFirstBySourceIdAndSubjectIdOrderByIdDesc(source.getId(), subjectId))
                .map(event -> new EventStatus(sourceName, subjectId, event.getExternalId(), outcome(event),
                        event.getProcessingNote(), event.getProcessedAt(), event.getIncidentId() == null ? null
                        : incidentReporting.overview(event.getIncidentId()).orElse(null)));
    }

    private static String outcome(EventRepository.EventState event) {
        if (event.getProcessedAt() == null) {
            return PENDING;
        }
        if (event.getIncidentId() != null) {
            return INCIDENT;
        }
        String note = event.getProcessingNote();
        return note == null ? "NONE" : note.split(":", 2)[0];
    }
}
