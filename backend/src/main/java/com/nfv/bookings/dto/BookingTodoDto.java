package com.nfv.bookings.dto;

import com.nfv.bookings.entities.TodoKind;
import com.nfv.bookings.entities.TodoReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Schema(description = "Something to do about a booking after the trip changed, with a copy of the booking. CANCEL: "
        + "cancel it at the provider (cancellableInApp: in the app, POST /booking-todos/{id}/refund-cancellation). "
        + "NOTIFY_LATE_ARRIVAL: the arrival moves to newArrivalOn, tell the accommodation.")
public record BookingTodoDto(
        long id,
        long tripId,
        @Schema(description = "null once the booking is deleted, e.g. with its stop") @Nullable Long bookingId,
        TodoKind kind,
        TodoReason reason,
        String title,
        @Nullable String provider,
        @Nullable String reference,
        @Nullable String manageUrl,
        @Nullable String phone,
        @Nullable String email,
        @Nullable OffsetDateTime startsAt,
        @Nullable OffsetDateTime endsAt,
        @Schema(description = "Free cancellation until then") @Nullable OffsetDateTime cancellationDeadline,
        @Schema(description = "Booked and paid in the app") boolean bookedInApp,
        @Schema(description = "Open cancellation the app can make, with a refund, also after the booking was "
                + "deleted") boolean cancellableInApp,
        @Schema(description = "NOTIFY_LATE_ARRIVAL: day of the new arrival, local") @Nullable LocalDate newArrivalOn,
        OffsetDateTime createdAt,
        @Schema(description = "null while open") @Nullable OffsetDateTime doneAt) {
}
