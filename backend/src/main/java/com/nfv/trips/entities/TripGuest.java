package com.nfv.trips.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The person hotels of the trip are booked for, remembered at the user's wish and suggested for every booking of the
 * trip. Personal data: not kept in the history of the trip, gone when the user forgets it or deletes the trip.
 */
@Embeddable
public record TripGuest(
        @Column(name = "guest_first_name", length = 100) String firstName,
        @Column(name = "guest_last_name", length = 100) String lastName,
        @Column(name = "guest_email") String email,
        @Column(name = "guest_phone", length = 64) String phone) {
}
