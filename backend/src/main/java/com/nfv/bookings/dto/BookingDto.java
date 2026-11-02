package com.nfv.bookings.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.nfv.bookings.entities.BookingSource;
import com.nfv.bookings.entities.BookingStatus;
import com.nfv.bookings.entities.BookingType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Schema(description = "Booking at a stop. Points in time carry the offset of the place (for accommodations: of the "
        + "accommodation), show them in that local time.")
public record BookingDto(long id, long stopId, BookingType type, BookingStatus status, BookingSource source,
                         String title, @Nullable String provider, @Nullable String reference,
                         @Nullable String manageUrl,
                         @Schema(description = "Check-in for accommodations") @Nullable OffsetDateTime startsAt,
                         @Schema(description = "Check-out for accommodations") @Nullable OffsetDateTime endsAt,
                         @Schema(type = "string", format = "decimal", example = "412.50")
                         @JsonFormat(shape = JsonFormat.Shape.STRING) @Nullable BigDecimal price,
                         @Nullable String currency, @Nullable OffsetDateTime cancellationDeadline,
                         @Nullable String notes,
                         // no @Nullable: springdoc would turn the referenced schema itself into type null
                         @Schema(description = "Details for accommodations, absent for other types")
                         AccommodationDto accommodation) {
}
