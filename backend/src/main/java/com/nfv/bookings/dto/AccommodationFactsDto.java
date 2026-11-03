package com.nfv.bookings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

import java.time.LocalTime;
import java.util.List;

@Schema(description = "Contact and house rules of an accommodation, not stored. Times are local at the "
        + "accommodation. fromLiteApi/fromAssistant name the fields by origin; fields from the assistant were found "
        + "on the web and should be checked by the user.")
public record AccommodationFactsDto(@Nullable String addressLine, @Nullable String countryCode,
                                    @Nullable String phone, @Nullable String email, @Nullable String website,
                                    @Nullable LocalTime checkInFrom, @Nullable LocalTime checkInUntil,
                                    @Nullable LocalTime checkOutUntil, List<String> fromLiteApi,
                                    List<String> fromAssistant,
                                    @Schema(description = "Whether the assistant looked up missing fields")
                                    boolean researched,
                                    @Schema(description = "Web pages the assistant used") List<String> sources) {
}
