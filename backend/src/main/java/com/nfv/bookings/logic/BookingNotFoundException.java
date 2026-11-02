package com.nfv.bookings.logic;

import com.nfv.common.errorhandling.BusinessException;

/** Unknown booking or stop, or one of another user. */
public class BookingNotFoundException extends BusinessException {
    private final String code;

    private BookingNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public static BookingNotFoundException booking(long bookingId) {
        return new BookingNotFoundException("BOOKING_NOT_FOUND", "booking " + bookingId + " not found");
    }

    public static BookingNotFoundException stop(long stopId) {
        return new BookingNotFoundException("STOP_NOT_FOUND", "stop " + stopId + " not found");
    }

    public String getCode() {
        return code;
    }
}
