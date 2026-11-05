package com.nfv.incidents.entities;

import com.nfv.common.data.AbstractEntity;
import com.nfv.incidents.logic.RouteAssessor;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Effect of an {@link Incident} on exactly one route of a trip, at most one per incident and route. Points to the
 * first affected section of the route. Derived by {@code ConsequenceAssessment}, never edited by users. Route and
 * section are referenced by id (trips module); the database removes the consequence with them.
 * <p>
 * {@link #impact} describes the incident at that place, {@link #category} the effect on the arrival at the target
 * stop, see Docs/consequence-assessment.md.
 */
@Getter
@Entity
public class Consequence extends AbstractEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Incident incident;
    @Column(nullable = false)
    private long routeId;
    @Column(nullable = false)
    private long routeSectionId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Impact impact;
    /** Expected delay of the incident as given, {@code null} if unknown. */
    private @Nullable Duration delay;
    /** First window of the incident the passage falls into, for display ("closed 21:00–04:30"); none without. */
    private @Nullable OffsetDateTime windowStart;
    private @Nullable OffsetDateTime windowEnd;
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private @Nullable Category category;
    @Enumerated(EnumType.STRING)
    @Column(name = "match_kind", nullable = false, length = 16)
    private MatchKind match = MatchKind.DIRECT;
    /** Own delay for this route (wait at a closure, delay or assumption), {@code null}: not passable. */
    private @Nullable Duration assessedDelay;
    /** {@link #assessedDelay} is the assumption of the source, shown as estimated. */
    private boolean delayAssumed;
    /** Passage of the section, moved by the delays before it. */
    private @Nullable OffsetDateTime passageAt;
    /** Arrival at the target stop after this consequence, {@code null} if not passable or not planned. */
    private @Nullable OffsetDateTime expectedArrival;
    /** Arrival of the target stop when assessed, {@code null} without. */
    private @Nullable OffsetDateTime deadline;
    private @Nullable OffsetDateTime assessedAt;
    @Column(nullable = false)
    private OffsetDateTime detectedAt;
    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected Consequence() {
    }

    public Consequence(Incident incident, long routeId, RouteAssessor.Assessment assessment,
                       @Nullable OffsetDateTime deadline, OffsetDateTime detectedAt) {
        this.incident = incident;
        this.routeId = routeId;
        this.detectedAt = detectedAt;
        assessed(assessment, deadline, detectedAt);
    }

    /** Takes over a new assessment of the route, the id stays. */
    public void assessed(RouteAssessor.Assessment assessment, @Nullable OffsetDateTime deadline, OffsetDateTime at) {
        this.routeSectionId = assessment.sectionId();
        this.impact = incident.impact();
        this.delay = incident.getExpectedDelay();
        this.windowStart = assessment.window() == null ? null : assessment.window().startsAt();
        this.windowEnd = assessment.window() == null ? null : assessment.window().endsAt();
        this.category = assessment.category();
        this.match = assessment.match();
        this.assessedDelay = assessment.delay();
        this.delayAssumed = assessment.delayAssumed();
        this.passageAt = assessment.passageAt();
        this.expectedArrival = assessment.expectedArrival();
        this.deadline = deadline;
        this.assessedAt = at;
        this.updatedAt = at;
    }

    /** The route has passed the place, including the wait there; an impassable one counts as waiting max-wait. */
    public boolean isOver(OffsetDateTime now, Duration maxWait) {
        return passageAt != null && passageAt.plus(assessedDelay == null ? maxWait : assessedDelay).isBefore(now);
    }

    /** Not passable (closure without end or waiting longer than allowed). */
    public boolean isUnpassable() {
        return category != null && assessedDelay == null;
    }
}
