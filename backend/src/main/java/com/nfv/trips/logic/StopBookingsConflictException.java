package com.nfv.trips.logic;

import com.nfv.common.errorhandling.BusinessException;
import jakarta.annotation.Nullable;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Bookings at the stop would lie outside its new days. Carries what the user needs to decide: the bookings, and
 * arrival and departure extended to the days of the bookings (same time of day, {@code null} if unchanged).
 */
@Getter
public class StopBookingsConflictException extends BusinessException {
    private final List<OutsideBooking> bookings;
    private final @Nullable OffsetDateTime extendedArrivalAt;
    private final @Nullable OffsetDateTime extendedDepartureAt;

    public StopBookingsConflictException(List<OutsideBooking> bookings, @Nullable OffsetDateTime extendedArrivalAt,
                                         @Nullable OffsetDateTime extendedDepartureAt) {
        super(bookings.size() + " booking(s) at the stop lie outside its new days: "
                + String.join(", ", bookings.stream().map(OutsideBooking::title).toList()));
        this.bookings = List.copyOf(bookings);
        this.extendedArrivalAt = extendedArrivalAt;
        this.extendedDepartureAt = extendedDepartureAt;
    }

    /**
     * @param trimmable whether the booking can be shortened to the new days: not if booked in the app (the provider
     *                  booking cannot be changed) or if nothing would be left of it
     */
    public record OutsideBooking(long bookingId, String title, @Nullable OffsetDateTime startsAt,
                                 @Nullable OffsetDateTime endsAt, boolean trimmable) {
    }
}
