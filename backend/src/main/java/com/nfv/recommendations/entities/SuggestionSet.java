package com.nfv.recommendations.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Suggestions for one route from one scenario, so that API, notification and applying mean the same ones. Derived and
 * replaced, not historized. Route, consequence and incident are referenced by id (other modules), see
 * Docs/recommendations.md#datenmodell-v27__recommendationssql.
 */
@Getter
@Entity
public class SuggestionSet extends AbstractEntity {
    @Column(nullable = false)
    private long routeId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SuggestionTrigger trigger;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WaypointMode waypointMode;
    private @Nullable Long consequenceId;
    private @Nullable Long incidentId;
    @Column(nullable = false)
    private int requestedCount;
    /** Departure at the start of the scenario. */
    @Column(nullable = false)
    private OffsetDateTime departureAt;
    /** Hash over everything the suggestions were computed from, equal hash → reuse the set. */
    @Column(nullable = false, length = 64)
    private String basisHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SuggestionStatus status;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    @Column(nullable = false)
    private OffsetDateTime expiresAt;
    @OneToMany(mappedBy = "suggestionSet", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("rank")
    private List<RouteOption> options = new ArrayList<>();

    protected SuggestionSet() {
    }

    public SuggestionSet(long routeId, SuggestionTrigger trigger, WaypointMode waypointMode, int requestedCount,
                         OffsetDateTime departureAt, String basisHash, OffsetDateTime createdAt,
                         OffsetDateTime expiresAt) {
        this.routeId = routeId;
        this.trigger = trigger;
        this.waypointMode = waypointMode;
        this.requestedCount = requestedCount;
        this.departureAt = departureAt;
        this.basisHash = basisHash;
        this.status = SuggestionStatus.CURRENT;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** Adds the option with the next rank, starting at 1. */
    public RouteOption add(RouteOption.Values values) {
        RouteOption option = new RouteOption(this, options.size() + 1, values);
        options.add(option);
        return option;
    }

    /** {@code CURRENT} and not expired at {@code now}. */
    public boolean isCurrent(OffsetDateTime now) {
        return status == SuggestionStatus.CURRENT && now.isBefore(expiresAt);
    }

    /** Status as the user sees it: {@code EXPIRED} once past {@code expiresAt}, without a job to set it. */
    public SuggestionStatus statusAt(OffsetDateTime now) {
        return status == SuggestionStatus.CURRENT && !now.isBefore(expiresAt) ? SuggestionStatus.EXPIRED : status;
    }

    public void stale() {
        if (status == SuggestionStatus.CURRENT) {
            status = SuggestionStatus.STALE;
        }
    }

    /** One option was applied; also from {@code STALE}, as applying changes the route itself. */
    public void applied(RouteOption option, OffsetDateTime now) {
        if (option.getSuggestionSet() != this) {
            throw new IllegalArgumentException("option of another set");
        }
        option.applied(now);
        status = SuggestionStatus.APPLIED;
    }
}