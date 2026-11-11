package com.nfv.bookings.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Criteria filled from the booking. Dates local at the accommodation, checkIn/checkOut are the "
        + "booked days even if check-in is before earliestCheckIn, maxPrice is the total for the stay. missing: fields the user has to enter (location, checkIn, checkOut, guests).")
public record AlternativeCriteriaDto(@Nullable Double latitude, @Nullable Double longitude,
                                     @Schema(description = "Address or name the coordinates belong to")
                                     @Nullable String placeName,
                                     int radiusMeters, @Nullable LocalDate checkIn, @Nullable LocalDate checkOut,
                                     @Schema(description = "Today at the accommodation: the earliest day a stay can "
                                             + "be booked from")
                                     LocalDate earliestCheckIn,
                                     @Nullable Integer guests, int rooms,
                                     @Schema(type = "string", format = "decimal", example = "412.50")
                                     @JsonFormat(shape = JsonFormat.Shape.STRING) @Nullable BigDecimal maxPrice,
                                     String currency,
                                     @Schema(description = "LiteAPI id of the current accommodation, if known")
                                     @Nullable String excludeHotelId,
                                     @Schema(description = "Stars of the current accommodation (rounded down) if "
                                             + "LiteAPI knows it, as minimum for the search")
                                     @Nullable Integer minStars,
                                     List<String> missing) {
}
