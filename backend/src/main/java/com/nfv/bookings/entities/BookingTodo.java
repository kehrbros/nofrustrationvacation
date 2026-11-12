package com.nfv.bookings.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.Getter;
import org.hibernate.annotations.TimeZoneStorage;
import org.hibernate.annotations.TimeZoneStorageType;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Something the user has to do about a booking after the trip changed, e.g. cancel an accommodation that is not
 * needed any more at its provider, see Docs/domain.md#bookings. Keeps a copy of what is needed for that, so it stays
 * when the booking is deleted with its stop ({@code booking_id} then becomes {@code null}). Not audited.
 */
@Getter
@Entity
public class BookingTodo extends AbstractEntity {
    @Column(nullable = false)
    private long tripId;
    /** {@code null} once the booking is deleted. */
    private @Nullable Long bookingId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TodoKind kind;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TodoReason reason;
    @Column(nullable = false)
    private String title;
    private @Nullable String provider;
    private @Nullable String reference;
    @Column(length = 1000)
    private @Nullable String manageUrl;
    @Column(length = 64)
    private @Nullable String phone;
    private @Nullable String email;
    @TimeZoneStorage(TimeZoneStorageType.COLUMN)
    private @Nullable OffsetDateTime startsAt;
    @TimeZoneStorage(TimeZoneStorageType.COLUMN)
    private @Nullable OffsetDateTime endsAt;
    @TimeZoneStorage(TimeZoneStorageType.COLUMN)
    private @Nullable OffsetDateTime cancellationDeadline;
    /** Booked and paid in the app: it can be cancelled there, with a refund. */
    private boolean bookedInApp;
    /**
     * The reservation it was booked and paid with in the app; it stays when the booking is deleted, so the to-do can
     * still cancel it with a refund. {@code null} if not booked in the app.
     */
    private @Nullable Long reservationId;
    /** {@code NOTIFY_LATE_ARRIVAL}: the day of the new arrival, local at the stop. */
    private @Nullable LocalDate newArrivalOn;
    @Column(nullable = false)
    private OffsetDateTime createdAt;
    private @Nullable OffsetDateTime doneAt;

    protected BookingTodo() {
    }

    public BookingTodo(long tripId, Booking booking, @Nullable String phone, @Nullable String email, TodoKind kind,
                       TodoReason reason, @Nullable Long reservationId, @Nullable LocalDate newArrivalOn,
                       OffsetDateTime createdAt) {
        this.tripId = tripId;
        this.bookingId = booking.getId();
        this.kind = kind;
        this.reason = reason;
        this.title = booking.getTitle();
        this.provider = booking.getProvider();
        this.reference = booking.getReference();
        this.manageUrl = booking.getManageUrl();
        this.phone = phone;
        this.email = email;
        this.startsAt = booking.getStartsAt();
        this.endsAt = booking.getEndsAt();
        this.cancellationDeadline = booking.getCancellationDeadline();
        this.bookedInApp = reservationId != null;
        this.reservationId = reservationId;
        this.newArrivalOn = newArrivalOn;
        this.createdAt = createdAt;
    }

    public boolean isOpen() {
        return doneAt == null;
    }

    /** Open cancellation of a booking made and paid in the app: the app can cancel it, with a refund. */
    public boolean isCancellableInApp() {
        return kind == TodoKind.CANCEL && isOpen() && reservationId != null;
    }

    public void done(OffsetDateTime at) {
        if (doneAt == null) {
            doneAt = at;
        }
    }

    public void reopen() {
        doneAt = null;
    }

    /** The booking is deleted right after, e.g. with its stop. */
    public void detach() {
        bookingId = null;
    }
}
