package com.nfv.bookings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

@Schema(description = "Accommodation found by name (LiteAPI hotel data), for the autocompletion of the name")
public record AccommodationSuggestionDto(@Schema(description = "LiteAPI hotel id") String hotelId, String name,
                                         @Nullable String addressLine, @Nullable String countryCode,
                                         @Nullable Double latitude, @Nullable Double longitude,
                                         @Nullable Double stars) {
}
