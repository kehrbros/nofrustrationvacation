package com.vacantagent.trips.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;
import org.hibernate.annotations.TimeZoneStorage;
import org.hibernate.annotations.TimeZoneStorageType;
import org.hibernate.envers.Audited;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Journey of a user along {@link Stop}s, connected by {@link Route}s, see Docs/domain.md. The period keeps the offset
 * the user entered it in (own {@code _tz} column, PostgreSQL itself stores only the instant).
 */
@Getter
@Entity
@Audited
public class Trip extends AbstractEntity {
    @Column(nullable = false)
    private long appUserId;
    @Column(nullable = false)
    private String name;
    /** Begin of the trip, inclusive. */
    @Column(nullable = false)
    @TimeZoneStorage(TimeZoneStorageType.COLUMN)
    private OffsetDateTime startAt;
    /** End of the trip, inclusive. */
    @Column(nullable = false)
    @TimeZoneStorage(TimeZoneStorageType.COLUMN)
    private OffsetDateTime endAt;
    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected Trip() {
    }

    public Trip(long appUserId, String name, OffsetDateTime startAt, OffsetDateTime endAt, OffsetDateTime createdAt) {
        this.appUserId = appUserId;
        this.name = name;
        reschedule(startAt, endAt);
        this.createdAt = createdAt;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void reschedule(OffsetDateTime startAt, OffsetDateTime endAt) {
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        if (endAt.isBefore(startAt)) {
            throw new IllegalArgumentException("trip ends at " + endAt + " before it starts at " + startAt);
        }
        this.startAt = startAt;
        this.endAt = endAt;
    }

    /** Where the trip stands at the given point in time. Compares instants, the offsets do not matter. */
    public TripPhase phaseAt(OffsetDateTime now) {
        if (now.isBefore(startAt)) {
            return TripPhase.UPCOMING;
        }
        return now.isAfter(endAt) ? TripPhase.PAST : TripPhase.CURRENT;
    }
}
