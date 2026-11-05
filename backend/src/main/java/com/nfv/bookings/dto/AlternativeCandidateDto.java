package com.nfv.bookings.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Schema(description = "Offer of a hotel that meets all criteria. price is the total for the stay including the fee.")
public record AlternativeCandidateDto(String hotelId, String name, @Nullable String address, double latitude,
                                      double longitude, int distanceMeters, @Nullable Double stars,
                                      @Nullable Double rating, @Nullable String photoUrl, String offerId,
                                      @Nullable String roomName, @Nullable String boardName, boolean refundable,
                                      @Schema(description = "Free cancellation until then, local at the hotel")
                                      @Nullable LocalDateTime freeCancellationUntil,
                                      @Schema(type = "string", format = "decimal", example = "189.00")
                                      @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal price,
                                      String currency,
                                      @Schema(description = "Within 50 m of the current accommodation, may be the same place")
                                      boolean possiblySame,
                                      @Schema(description = "Why the assistant ranks it there, absent without ranking")
                                      @Nullable String reason) {
}
