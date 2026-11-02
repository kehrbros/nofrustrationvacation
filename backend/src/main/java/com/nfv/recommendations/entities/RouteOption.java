package com.nfv.recommendations.entities;

import com.nfv.common.data.AbstractEntity;
import com.nfv.incidents.entities.Impact;
import jakarta.annotation.Nullable;
import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import lombok.Getter;
import org.locationtech.jts.geom.LineString;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** One suggestion of a {@link SuggestionSet}: how to drive, when to arrive, what still happens on the way. */
@Getter
@Entity
public class RouteOption extends AbstractEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private SuggestionSet suggestionSet;
    /** 1 is the best, unique per set. */
    @Column(nullable = false)
    private int rank;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OptionKind kind;
    @ElementCollection
    @CollectionTable(name = "route_option_label", joinColumns = @JoinColumn(name = "route_option_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "label", nullable = false, length = 32)
    private Set<OptionLabel> labels = EnumSet.noneOf(OptionLabel.class);
    /** Main road of the part that differs from the fastest way, e.g. {@code SH 2}. */
    private @Nullable String via;
    @Column(nullable = false)
    private OffsetDateTime departureAt;
    /** Arrival at the end stop including waiting and delays. */
    @Column(nullable = false)
    private OffsetDateTime arrivalAt;
    @Column(name = "distance_m", nullable = false)
    private double distanceMeters;
    /** Driving time without waiting and delays. */
    @Column(nullable = false)
    private Duration duration;
    @Column(nullable = false)
    private Duration waiting;
    @Column(nullable = false)
    private Duration delay;
    /** Part of the distance shared with the planned route, {@code null} without one. */
    private @Nullable Double sharedRatio;
    @Column(nullable = false, columnDefinition = "geometry(LineString, 4326)")
    private LineString geometry;
    private @Nullable OffsetDateTime appliedAt;
    @OneToMany(mappedBy = "routeOption", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    private List<RouteOptionPoint> points = new ArrayList<>();
    @OneToMany(mappedBy = "routeOption", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<RouteOptionConflict> conflicts = new ArrayList<>();

    /** Everything but set and rank, built by the ranking. */
    public record Values(OptionKind kind, Set<OptionLabel> labels, @Nullable String via, OffsetDateTime departureAt,
                         OffsetDateTime arrivalAt, double distanceMeters, Duration duration, Duration waiting,
                         Duration delay, @Nullable Double sharedRatio, LineString geometry, List<Point> points,
                         List<Conflict> conflicts) {
    }

    /** @param waypointId existing waypoint of the user that this point keeps */
    public record Point(double latitude, double longitude, @Nullable String roadHint, @Nullable Long waypointId) {
    }

    public record Conflict(long incidentId, Impact impact, @Nullable Duration delay) {
    }

    protected RouteOption() {
    }

    RouteOption(SuggestionSet suggestionSet, int rank, Values values) {
        this.suggestionSet = suggestionSet;
        this.rank = rank;
        this.kind = values.kind();
        this.labels.addAll(values.labels());
        this.via = values.via() == null || values.via().length() <= 255 ? values.via() : values.via().substring(0, 255);
        this.departureAt = values.departureAt();
        this.arrivalAt = values.arrivalAt();
        this.distanceMeters = values.distanceMeters();
        this.duration = values.duration();
        this.waiting = values.waiting();
        this.delay = values.delay();
        this.sharedRatio = values.sharedRatio();
        this.geometry = values.geometry();
        for (int i = 0; i < values.points().size(); i++) {
            Point point = values.points().get(i);
            points.add(new RouteOptionPoint(this, i, point.latitude(), point.longitude(), point.roadHint(),
                    point.waypointId()));
        }
        values.conflicts().forEach(conflict -> conflicts.add(new RouteOptionConflict(this, conflict.incidentId(),
                conflict.impact(), conflict.delay())));
    }

    void applied(OffsetDateTime now) {
        this.appliedAt = now;
    }
}