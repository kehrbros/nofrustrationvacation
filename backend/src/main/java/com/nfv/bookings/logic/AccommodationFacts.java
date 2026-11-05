package com.nfv.bookings.logic;

import jakarta.annotation.Nullable;

import java.time.LocalTime;
import java.util.List;

/**
 * Contact and house rules of an accommodation, not stored.
 *
 * @param fromLiteApi   fields taken from the LiteAPI hotel data
 * @param fromAssistant fields the assistant found on the web, to be checked by the user
 * @param researched    whether the assistant was asked
 * @param sources       web pages the assistant used
 */
public record AccommodationFacts(@Nullable String addressLine, @Nullable String countryCode, @Nullable String phone,
                                 @Nullable String email, @Nullable String website, @Nullable LocalTime checkInFrom,
                                 @Nullable LocalTime checkInUntil, @Nullable LocalTime checkOutUntil,
                                 List<String> fromLiteApi, List<String> fromAssistant, boolean researched,
                                 List<String> sources) {
}
