package com.nfv.bookings.logic;

import jakarta.annotation.Nullable;

/** Accommodation whose facts are looked up; hotelId if it was chosen from the LiteAPI suggestions. */
public record AccommodationQuery(@Nullable String hotelId, String name, @Nullable String addressLine,
                                 @Nullable String countryCode, @Nullable Double latitude, @Nullable Double longitude) {
}
