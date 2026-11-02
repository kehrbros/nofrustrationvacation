package com.nfv.bookings.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Schema(description = "Accommodation booking read from a confirmation, not stored. found=false: no booking in the "
        + "input. Times are local at the accommodation, uncertainFields should be checked by the user.")
public record AccommodationDraftDto(boolean found, @Nullable String name, @Nullable String addressLine,
                                    @Nullable String countryCode, @Nullable String phone, @Nullable String email,
                                    @Nullable String website, @Nullable String provider, @Nullable String reference,
                                    @Nullable String manageUrl, @Nullable LocalDate checkInDate,
                                    @Nullable LocalTime checkInTime, @Nullable LocalDate checkOutDate,
                                    @Nullable LocalTime checkOutTime, @Nullable LocalTime checkInFrom,
                                    @Nullable LocalTime checkInUntil, @Nullable LocalTime checkOutUntil,
                                    @Nullable Integer guests, @Nullable Integer rooms,
                                    @Schema(type = "string", format = "decimal", example = "412.50")
                                    @JsonFormat(shape = JsonFormat.Shape.STRING) @Nullable BigDecimal price,
                                    @Nullable String currency, @Nullable LocalDateTime cancellationDeadline,
                                    List<String> uncertainFields) {
}
