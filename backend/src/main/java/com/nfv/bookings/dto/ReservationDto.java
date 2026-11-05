package com.nfv.bookings.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.nfv.bookings.entities.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "Booking of an alternative via LiteAPI, paid with PayPal; price (including fee) is what the "
        + "user pays")
public record ReservationDto(long id, long tripId, long stopId, @Nullable Long replacesBookingId,
                             @Nullable Long bookingId, ReservationStatus status, String hotelName,
                             @Nullable String hotelAddress, LocalDate checkIn, LocalDate checkOut, int guests,
                             int rooms,
                             @Schema(type = "string", format = "decimal", example = "189.00")
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal price,
                             @Schema(type = "string", format = "decimal", example = "17.18")
                             @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal fee,
                             String currency, boolean refundable, @Nullable LocalDateTime freeCancellationUntil,
                             @Schema(description = "Approve the payment there, only in the answer that creates it")
                             @Nullable String approveUrl,
                             @Nullable String failure,
                             // no @Nullable: springdoc would turn the referenced schema itself into type null
                             @Schema(description = "The created booking once booked") BookingDto booking) {
}
