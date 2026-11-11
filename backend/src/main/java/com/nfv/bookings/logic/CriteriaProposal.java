package com.nfv.bookings.logic;

import jakarta.annotation.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Criteria filled from an accommodation booking for the user to check, see Docs/accommodation-alternatives.md.
 *
 * @param checkIn         check-in day of the booking, local at the accommodation, also when it is before
 *                        {@code earliestCheckIn}
 * @param checkOut        check-out day of the booking
 * @param earliestCheckIn today at the accommodation: no stay can be booked before it
 * @param maxPrice        price of the whole booking
 * @param missing         fields the booking does not provide and the user has to enter: {@code location},
 *                        {@code checkIn}, {@code checkOut}, {@code guests}
 * @param minStars        stars of the current accommodation (rounded down) if LiteAPI knows it, else null
 */
public record CriteriaProposal(@Nullable Double latitude, @Nullable Double longitude, @Nullable String placeName,
                               int radiusMeters, @Nullable LocalDate checkIn, @Nullable LocalDate checkOut,
                               LocalDate earliestCheckIn,
                               @Nullable Integer guests, int rooms, @Nullable BigDecimal maxPrice, String currency,
                               @Nullable String excludeHotelId, @Nullable Integer minStars, List<String> missing) {

    /**
     * The part of the stay that can still be booked: check-in moved to {@code earliestCheckIn} if the stay is running
     * already, maximum price reduced to the remaining nights. Check-out is null if no night is left.
     */
    public CriteriaProposal fromEarliest() {
        if (checkIn == null || !checkIn.isBefore(earliestCheckIn)) {
            return this;
        }
        LocalDate remainingOut = checkOut != null && checkOut.isAfter(earliestCheckIn) ? checkOut : null;
        BigDecimal remainingPrice = maxPrice;
        if (maxPrice != null && checkOut != null && remainingOut != null) {
            long bookedNights = ChronoUnit.DAYS.between(checkIn, checkOut);
            long nights = ChronoUnit.DAYS.between(earliestCheckIn, remainingOut);
            if (bookedNights > 0 && nights < bookedNights) {
                remainingPrice = maxPrice.multiply(BigDecimal.valueOf(nights))
                        .divide(BigDecimal.valueOf(bookedNights), 2, RoundingMode.UP);
            }
        }
        return new CriteriaProposal(latitude, longitude, placeName, radiusMeters, earliestCheckIn, remainingOut,
                earliestCheckIn, guests, rooms, remainingPrice, currency, excludeHotelId, minStars, missing);
    }
}
