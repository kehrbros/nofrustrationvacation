package com.nfv.bookings.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Offer picked from the search with its criteria and the person it is booked for. Name, e-mail "
        + "and phone go to LiteAPI and are kept only until the booking is made.")
public record ReservationInputDto(String hotelId, String offerId, LocalDate checkIn, LocalDate checkOut,
                                  Integer guests, Integer rooms,
                                  @Schema(type = "string", format = "decimal", example = "412.50")
                                  @JsonFormat(shape = JsonFormat.Shape.STRING) @Nullable BigDecimal maxPrice,
                                  String currency, String firstName, String lastName, String email, String phone) {
}
