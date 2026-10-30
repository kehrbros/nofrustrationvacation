package com.vacantagent.bookings.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import org.hibernate.envers.Audited;

import java.time.OffsetDateTime;

/** Reservation at a stop of a trip, e.g. a hotel. Refers to the stop by id, trips is a module of its own. */
@Getter
@Entity
@Audited
public class Booking extends AbstractEntity {
    @Column(nullable = false)
    private long stopId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingType type;
    @Column(nullable = false)
    private String title;
    /** Hotel, airline etc. */
    private @Nullable String provider;
    /** Booking or confirmation number at the provider. */
    private @Nullable String reference;
    private @Nullable OffsetDateTime startsAt;
    private @Nullable OffsetDateTime endsAt;

    protected Booking() {
    }

    public Booking(long stopId, BookingType type, String title, @Nullable String provider, @Nullable String reference,
                   @Nullable OffsetDateTime startsAt, @Nullable OffsetDateTime endsAt) {
        this.stopId = stopId;
        this.type = type;
        this.title = title;
        this.provider = provider;
        this.reference = reference;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }
}
