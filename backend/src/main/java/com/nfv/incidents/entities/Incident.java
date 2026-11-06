package com.nfv.incidents.entities;

import com.nfv.common.data.AbstractEntity;
import com.nfv.incidents.logic.IncidentDetails;
import com.nfv.incidents.logic.IncidentOverlap;
import com.nfv.incidents.logic.TimeWindow;
import jakarta.annotation.Nullable;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import lombok.Getter;
import org.hibernate.envers.Audited;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Something that affects travelling on one or more segments of the road network during {@code [validFrom, validTo]}.
 * Located by segment ids, the network is a module of its own. Incidents at accommodations are still open, see
 * Docs/domain.md.
 */
@Getter
@Entity
@Audited
public class Incident extends AbstractEntity {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IncidentType type;
    @Column(length = 2000)
    private @Nullable String description;
    @Column(nullable = false)
    private OffsetDateTime validFrom;
    /** {@code null}: until further notice. */
    private @Nullable OffsetDateTime validTo;
    /** Delay expected when passing, {@code null} if unknown. */
    private @Nullable Duration expectedDelay;
    /**
     * Delay the source assumes without giving a figure (e.g. NZTA "Delays": 15 min), shown as estimated; {@code null}
     * without assumption. See Docs/traffic-feed.md#verzögerung-ohne-angabe.
     */
    private @Nullable Duration assumedDelay;
    @ElementCollection
    @CollectionTable(name = "incident_segment", joinColumns = @JoinColumn(name = "incident_id"))
    @Column(name = "segment_id", nullable = false)
    private Set<Long> segmentIds = new HashSet<>();
    /**
     * Periods within the validity in which the incident applies, e.g. closed at night only. Empty: the whole validity,
     * which stays the envelope for queries. See Docs/traffic-feed.md#zeitfenster.
     */
    @ElementCollection
    @CollectionTable(name = "incident_window", joinColumns = @JoinColumn(name = "incident_id"))
    private Set<IncidentWindow> windows = new HashSet<>();
    /** Vehicles the incident applies to; {@code HEAVY_ONLY} does not affect cars. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VehicleScope vehicleScope = VehicleScope.ALL;
    /**
     * Closure whose times are only in the text and could not be read yet: reported as {@code OTHER}, assessed as
     * {@code DELAYED} without a delay, "check times". See Docs/traffic-feed.md#verarbeitung-trafficeventprocessor.
     */
    @Column(nullable = false)
    private boolean timesUnconfirmed;

    protected Incident() {
    }

    public Incident(IncidentType type, @Nullable String description, OffsetDateTime validFrom,
                    @Nullable OffsetDateTime validTo, @Nullable Duration expectedDelay, Set<Long> segmentIds) {
        this(IncidentDetails.of(type, description, validFrom, validTo, expectedDelay), segmentIds);
    }

    public Incident(IncidentDetails details, Set<Long> segmentIds) {
        change(details, segmentIds);
    }

    /** Changes everything but type, assumed delay, windows and vehicle scope. */
    public void change(@Nullable String description, OffsetDateTime validFrom, @Nullable OffsetDateTime validTo,
                       @Nullable Duration expectedDelay, Set<Long> segmentIds) {
        change(new IncidentDetails(type, description, validFrom, validTo, expectedDelay, assumedDelay, timeWindows(),
                vehicleScope, timesUnconfirmed), segmentIds);
    }

    /** Changes everything, also the type (e.g. roadworks that turn into a full closure); the id stays. */
    public void change(IncidentDetails details, Set<Long> segmentIds) {
        if (segmentIds.isEmpty()) {
            throw new IllegalArgumentException("an incident is located on at least one segment");
        }
        if (details.validTo() != null && details.validTo().isBefore(details.validFrom())) {
            throw new IllegalArgumentException("incident ends before it starts");
        }
        this.type = details.type();
        this.assumedDelay = details.assumedDelay();
        this.description = details.description();
        this.validFrom = details.validFrom();
        this.validTo = details.validTo();
        this.expectedDelay = details.expectedDelay();
        this.vehicleScope = details.vehicleScope();
        this.timesUnconfirmed = details.timesUnconfirmed();
        // copy first: the caller may pass the segments of this incident
        Set<Long> newSegments = Set.copyOf(segmentIds);
        this.segmentIds.clear();
        this.segmentIds.addAll(newSegments);
        // only the difference: the history (Envers) should not see unchanged windows as removed and added
        Set<IncidentWindow> newWindows = details.windows().stream().map(IncidentWindow::new)
                .collect(Collectors.toSet());
        if (!this.windows.equals(newWindows)) {
            this.windows.retainAll(newWindows);
            this.windows.addAll(newWindows);
        }
    }

    /** All values except the segments, e.g. to change only some of them. */
    public IncidentDetails details() {
        return new IncidentDetails(type, description, validFrom, validTo, expectedDelay, assumedDelay, timeWindows(),
                vehicleScope, timesUnconfirmed);
    }

    /** Windows ordered by start. */
    public List<TimeWindow> timeWindows() {
        return windows.stream().map(IncidentWindow::toTimeWindow)
                .sorted(Comparator.comparing(TimeWindow::startsAt)).toList();
    }

    /** Whether a passage during {@code [start, end]} falls into the validity and, if any, into a window. */
    public boolean overlaps(OffsetDateTime start, OffsetDateTime end) {
        return IncidentOverlap.overlaps(validFrom, validTo, timeWindows(), start, end);
    }

    /** Earliest window the passage falls into, empty without windows. */
    public Optional<TimeWindow> firstWindow(OffsetDateTime start, OffsetDateTime end) {
        return IncidentOverlap.firstWindow(timeWindows(), start, end);
    }

    public Impact impact() {
        return IncidentOverlap.impactOf(type);
    }
}
