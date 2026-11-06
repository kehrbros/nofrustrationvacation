package com.nfv.bookings.logic;

import com.nfv.common.errorhandling.BusinessException;

/** The booking cannot be cancelled in the app: not booked here, not refundable or not finished. */
public class CancellationNotPossibleException extends BusinessException {
    public CancellationNotPossibleException(String message) {
        super(message);
    }
}
