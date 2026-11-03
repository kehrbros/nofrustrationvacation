package com.nfv.bookings.logic;

import com.nfv.common.errorhandling.BusinessException;

/** Unknown reservation or one of another user. */
public class ReservationNotFoundException extends BusinessException {
    public ReservationNotFoundException(long reservationId) {
        super("reservation " + reservationId + " not found");
    }
}
