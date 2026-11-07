package com.nfv.advisor.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Schema(description = "Offer of a hotel as the agent found it. Booking checks it again. With replacesBookingId it is "
        + "booked like an alternative: POST /accommodation-bookings/{replacesBookingId}/alternative-reservations with "
        + "hotelId, offerId, checkIn, checkOut, guests, rooms, currency and price as maxPrice. A stay without booking "
        + "to replace cannot be booked yet.")
public record ProposalStayDto(
        String hotelId,
        String offerId,
        String hotelName,
        double latitude,
        double longitude,
        @Schema(description = "Area or target stop the search looked around") String place,
        @Schema(description = "From place, straight line") int distanceMeters,
        LocalDate checkIn,
        LocalDate checkOut,
        int guests,
        int rooms,
        @Schema(type = "string", format = "decimal", example = "189.00", description = "Total for the stay incl. fee")
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal price,
        String currency,
        boolean refundable,
        @Schema(description = "Local at the hotel") @Nullable LocalDateTime freeCancellationUntil,
        @Schema(description = "TARGET_STAY: the accommodation booked at the target stop that this one replaces")
        @Nullable Long replacesBookingId) {
}
