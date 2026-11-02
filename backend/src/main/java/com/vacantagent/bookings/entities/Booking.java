package com.vacantagent.bookings.entities;

import com.vacantagent.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import org.hibernate.annotations.TimeZoneStorage;
import org.hibernate.annotations.TimeZoneStorageType;
import org.hibernate.envers.Audited;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Reservation at a stop of a trip, e.g. a hotel. Refers to the stop by id, trips is a module of its own. Details of
 * an accommodation are in {@link AccommodationDetails}. Points in time keep the offset of the place (own {@code _tz}
 * columns), see Docs/domain.md#bookings.
 */
@Getter
@Entity
@Audited
public class Booking extends AbstractEntity {
    @Column(nullable = false)
    private long stopId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingType type;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingStatus status;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BookingSource source;
    @Column(nullable = false)
    private String title;
    /** Booking.com, airline, hotel etc. */
    private @Nullable String provider;
    /** Booking or confirmation number at the provider. The PIN is never stored. */
    private @Nullable String reference;
    /** Where the user manages the booking at the provider. */
    @Column(length = 1000)
    private @Nullable String manageUrl;
    /** Check-in for accommodations. */
    @TimeZoneStorage(TimeZoneStorageType.COLUMN)
    private @Nullable OffsetDateTime startsAt;
    /** Check-out for accommodations. */
    @TimeZoneStorage(TimeZoneStorageType.COLUMN)
    private @Nullable OffsetDateTime endsAt;
    @Column(precision = 12, scale = 2)
    private @Nullable BigDecimal price;
    /** ISO 4217, set whenever there is a price. */
    @Column(length = 3)
    private @Nullable String currency;
    /** Free cancellation until then. */
    @TimeZoneStorage(TimeZoneStorageType.COLUMN)
    private @Nullable OffsetDateTime cancellationDeadline;
    @Column(length = 2000)
    private @Nullable String notes;

    protected Booking() {
    }

    public Booking(long stopId, BookingType type, BookingSource source, BookingContent content) {
        this.stopId = stopId;
        this.type = type;
        this.source = source;
        this.status = BookingStatus.CONFIRMED;
        change(content);
    }

    /** Replaces everything the user can edit. */
    public void change(BookingContent content) {
        this.title = content.title();
        this.provider = content.provider();
        this.reference = content.reference();
        this.manageUrl = content.manageUrl();
        this.startsAt = content.startsAt();
        this.endsAt = content.endsAt();
        this.price = content.price();
        this.currency = content.currency();
        this.cancellationDeadline = content.cancellationDeadline();
        this.notes = content.notes();
    }

    public void cancel() {
        this.status = BookingStatus.CANCELLED;
    }

    /** What the user enters or confirms, already validated. */
    public record BookingContent(String title, @Nullable String provider, @Nullable String reference,
                                 @Nullable String manageUrl, @Nullable OffsetDateTime startsAt,
                                 @Nullable OffsetDateTime endsAt, @Nullable BigDecimal price,
                                 @Nullable String currency, @Nullable OffsetDateTime cancellationDeadline,
                                 @Nullable String notes) {
    }
}
