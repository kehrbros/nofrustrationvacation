package com.nfv.bookings.logic;

import com.nfv.common.errorhandling.BusinessException;
import jakarta.annotation.Nullable;
import lombok.Getter;

import java.time.OffsetDateTime;

/**
 * The changed booking would lie outside the days of its stop. Carries the stop's arrival and departure, both extended
 * to the days of the booking, and whether the booking can be shortened instead ({@link StopConflict}).
 */
@Getter
public class BookingOutsideStopException extends BusinessException {
    private final @Nullable OffsetDateTime stopArrivalAt;
    private final @Nullable OffsetDateTime stopDepartureAt;
    private final @Nullable OffsetDateTime extendedArrivalAt;
    private final @Nullable OffsetDateTime extendedDepartureAt;
    /** Not if booked in the app (the hotel booking stays) or if no night would be left. */
    private final boolean trimmable;

    public BookingOutsideStopException(@Nullable OffsetDateTime stopArrivalAt, @Nullable OffsetDateTime stopDepartureAt,
                                       @Nullable OffsetDateTime extendedArrivalAt,
                                       @Nullable OffsetDateTime extendedDepartureAt, boolean trimmable) {
        super("the booking lies outside the days of its stop");
        this.stopArrivalAt = stopArrivalAt;
        this.stopDepartureAt = stopDepartureAt;
        this.extendedArrivalAt = extendedArrivalAt;
        this.extendedDepartureAt = extendedDepartureAt;
        this.trimmable = trimmable;
    }
}
