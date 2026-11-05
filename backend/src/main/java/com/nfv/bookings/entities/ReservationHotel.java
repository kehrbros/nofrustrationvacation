package com.nfv.bookings.entities;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.time.LocalTime;

/** The hotel of a reservation as it becomes the accommodation of the booking, from LiteAPI's hotel data. */
@Embeddable
public record ReservationHotel(
        @Column(name = "hotel_name", nullable = false) String name,
        @Column(name = "hotel_address", length = 500) @Nullable String address,
        @Column(name = "hotel_country_code", length = 2) @Nullable String countryCode,
        @Column(name = "hotel_latitude") @Nullable Double latitude,
        @Column(name = "hotel_longitude") @Nullable Double longitude,
        @Column(name = "hotel_phone", length = 64) @Nullable String phone,
        @Column(name = "hotel_email") @Nullable String email,
        @Column(name = "hotel_website", length = 1000) @Nullable String website,
        @Nullable LocalTime checkInFrom,
        @Nullable LocalTime checkInUntil,
        @Nullable LocalTime checkOutUntil) {
}
