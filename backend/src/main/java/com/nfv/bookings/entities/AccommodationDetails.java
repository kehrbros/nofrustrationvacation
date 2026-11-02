package com.nfv.bookings.entities;

import com.nfv.common.data.AbstractEntity;
import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.envers.Audited;

import java.time.LocalTime;
import java.time.ZoneId;

/** Place and house rules of an accommodation booking, 0..1 per {@link Booking}, deleted with it. */
@Getter
@Entity
@Audited
@Table(name = "booking_accommodation")
public class AccommodationDetails extends AbstractEntity {
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false, unique = true)
    private Booking booking;
    @Column(nullable = false)
    private String name;
    @Column(length = 500)
    private @Nullable String addressLine;
    /** ISO 3166-1 alpha-2. */
    @Column(length = 2)
    private @Nullable String countryCode;
    private @Nullable Double latitude;
    private @Nullable Double longitude;
    /** IANA time zone of the place (from the coordinates, else the user's), check-in and check-out are local there. */
    @Column(nullable = false, length = 64)
    private String timeZone;
    @Column(length = 64)
    private @Nullable String phone;
    private @Nullable String email;
    @Column(length = 1000)
    private @Nullable String website;
    /** Where the place comes from, e.g. {@code osm:node/123}. */
    private @Nullable String placeRef;
    /** House rules, local time at the place. */
    private @Nullable LocalTime checkInFrom;
    private @Nullable LocalTime checkInUntil;
    private @Nullable LocalTime checkOutUntil;
    private @Nullable Integer guests;
    private @Nullable Integer rooms;

    protected AccommodationDetails() {
    }

    public AccommodationDetails(Booking booking, Place place, HouseRules rules) {
        this.booking = booking;
        change(place, rules);
    }

    public void change(Place place, HouseRules rules) {
        this.name = place.name();
        this.addressLine = place.addressLine();
        this.countryCode = place.countryCode();
        this.latitude = place.latitude();
        this.longitude = place.longitude();
        this.timeZone = place.timeZone().getId();
        this.phone = place.phone();
        this.email = place.email();
        this.website = place.website();
        this.placeRef = place.placeRef();
        this.checkInFrom = rules.checkInFrom();
        this.checkInUntil = rules.checkInUntil();
        this.checkOutUntil = rules.checkOutUntil();
        this.guests = rules.guests();
        this.rooms = rules.rooms();
    }

    public record Place(String name, @Nullable String addressLine, @Nullable String countryCode,
                        @Nullable Double latitude, @Nullable Double longitude, ZoneId timeZone,
                        @Nullable String phone, @Nullable String email, @Nullable String website,
                        @Nullable String placeRef) {
    }

    public record HouseRules(@Nullable LocalTime checkInFrom, @Nullable LocalTime checkInUntil,
                             @Nullable LocalTime checkOutUntil, @Nullable Integer guests, @Nullable Integer rooms) {
    }
}
