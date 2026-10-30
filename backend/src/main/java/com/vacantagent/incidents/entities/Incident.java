package com.vacantagent.incidents.entities;

import com.vacantagent.common.data.AbstractEntity;
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
import java.util.HashSet;
import java.util.Set;

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
    @ElementCollection
    @CollectionTable(name = "incident_segment", joinColumns = @JoinColumn(name = "incident_id"))
    @Column(name = "segment_id", nullable = false)
    private Set<Long> segmentIds = new HashSet<>();

    protected Incident() {
    }

    public Incident(IncidentType type, @Nullable String description, OffsetDateTime validFrom,
                    @Nullable OffsetDateTime validTo, @Nullable Duration expectedDelay, Set<Long> segmentIds) {
        this.type = type;
        change(description, validFrom, validTo, expectedDelay, segmentIds);
    }

    public void change(@Nullable String description, OffsetDateTime validFrom, @Nullable OffsetDateTime validTo,
                       @Nullable Duration expectedDelay, Set<Long> segmentIds) {
        if (segmentIds.isEmpty()) {
            throw new IllegalArgumentException("an incident is located on at least one segment");
        }
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("incident ends before it starts");
        }
        this.description = description;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.expectedDelay = expectedDelay;
        this.segmentIds.clear();
        this.segmentIds.addAll(segmentIds);
    }

    /** Whether a passage during {@code [start, end]} falls into the validity. */
    public boolean overlaps(OffsetDateTime start, OffsetDateTime end) {
        return !end.isBefore(validFrom) && (validTo == null || !start.isAfter(validTo));
    }
}
