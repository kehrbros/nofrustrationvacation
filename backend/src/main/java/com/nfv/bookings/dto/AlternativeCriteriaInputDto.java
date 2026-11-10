package com.nfv.bookings.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Criteria of a search. Dates local at the place, maxPrice is the total for the stay including "
        + "the fee (empty: no limit).")
public record AlternativeCriteriaInputDto(Double latitude, Double longitude, Integer radiusMeters,
                                          LocalDate checkIn, LocalDate checkOut, Integer guests, Integer rooms,
                                          @Schema(type = "string", format = "decimal", example = "412.50")
                                          @JsonFormat(shape = JsonFormat.Shape.STRING) @Nullable BigDecimal maxPrice,
                                          String currency, @Nullable String excludeHotelId,
                                          @Schema(description = "At least these stars (1 to 5), hotels without stars "
                                                  + "are left out; empty: no limit")
                                          @Nullable Integer minStars) {
}
