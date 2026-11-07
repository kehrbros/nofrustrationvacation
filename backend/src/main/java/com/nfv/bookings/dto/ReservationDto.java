package com.nfv.bookings.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.nfv.bookings.entities.ReservationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
                             @Schema(description = "PayPal order, for the PayPal button in the app (JS SDK)")
                             @Nullable String payPalOrderId,
                             @Nullable String failure,
                             @Schema(type = "string", format = "decimal", example = "189.00",
                                     description = "Refunded with PayPal after a cancellation")
                             @JsonFormat(shape = JsonFormat.Shape.STRING) @Nullable BigDecimal refundAmount,
                             @Schema(description = "The refund with PayPal is made") boolean refunded,
                             @Schema(description = "Routes planned again because the stop moved to the place of "
                                     + "the new hotel (more than 10 km away), only in the answer that booked it")
                             List<Long> replannedRouteIds,
                             // no @Nullable: springdoc would turn the referenced schema itself into type null
                             @Schema(description = "The created booking once booked") BookingDto booking) {
}
