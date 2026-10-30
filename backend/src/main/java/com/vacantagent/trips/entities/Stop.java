package com.vacantagent.trips.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
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
    private @Nullable OffsetDateTime arrivalAt;
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

    public void reschedule(@Nullable OffsetDateTime arrivalAt, @Nullable OffsetDateTime departureAt) {
        this.arrivalAt = arrivalAt;
        this.departureAt = departureAt;
    }
}
