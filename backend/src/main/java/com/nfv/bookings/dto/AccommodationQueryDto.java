package com.nfv.bookings.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Nullable;

@Schema(description = "Accommodation whose contact and house rules are looked up. hotelId if it was chosen from the "
        + "suggestions; name, address and coordinates help the assistant identify it.")
public record AccommodationQueryDto(@Nullable String hotelId, String name, @Nullable String addressLine,
                                    @Nullable String countryCode, @Nullable Double latitude,
                                    @Nullable Double longitude) {
}
