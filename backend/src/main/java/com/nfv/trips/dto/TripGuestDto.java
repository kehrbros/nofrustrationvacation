package com.nfv.trips.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Person the hotels of the trip are booked for, remembered at the user's wish")
public record TripGuestDto(String firstName, String lastName, String email, String phone) {
}
