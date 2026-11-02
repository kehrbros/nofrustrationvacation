package com.nfv.trips.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import org.hibernate.annotations.TimeZoneStorage;
import org.hibernate.annotations.TimeZoneStorageType;
import org.hibernate.envers.Audited;

import java.time.OffsetDateTime;

/** Place on a {@link Trip}, e.g. start, accommodation or destination. Ordered by {@link #position}. */
@Getter
@Entity
@Audited
public class Stop extends AbstractEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Trip trip;
    @Column(nullable = false)
    private int position;
    @Column(nullable = false)
    private String name;
    private @Nullable Double latitude;
    private @Nullable Double longitude;
    /** Local time at the stop, the offset is kept (own {@code _tz} column). */
    @TimeZoneStorage(TimeZoneStorageType.COLUMN)
    private @Nullable OffsetDateTime arrivalAt;
    @TimeZoneStorage(TimeZoneStorageType.COLUMN)
    private @Nullable OffsetDateTime departureAt;

    protected Stop() {
    }

    public Stop(Trip trip, int position, String name, @Nullable Double latitude, @Nullable Double longitude,
                @Nullable OffsetDateTime arrivalAt, @Nullable OffsetDateTime departureAt) {
        this.trip = trip;
        this.position = position;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        reschedule(arrivalAt, departureAt);
    }

    public void moveTo(int position) {
        this.position = position;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void relocate(@Nullable Double latitude, @Nullable Double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public void reschedule(@Nullable OffsetDateTime arrivalAt, @Nullable OffsetDateTime departureAt) {
        this.arrivalAt = arrivalAt;
        this.departureAt = departureAt;
    }
}
