package com.nfv.bookings.logic;

import jakarta.annotation.Nullable;

/** Accommodation found by name in the LiteAPI hotel data. */
public record AccommodationSuggestion(String hotelId, String name, @Nullable String addressLine,
                                      @Nullable String countryCode, @Nullable Double latitude,
                                      @Nullable Double longitude, @Nullable Double stars) {
}
