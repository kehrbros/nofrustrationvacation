package com.nfv.trips.logic;

import com.nfv.common.errorhandling.BusinessException;

/** The stop cannot be deleted right now, e.g. while the payment of a booking at it is running. */
public class StopInUseException extends BusinessException {
    public StopInUseException(String message) {
        super(message);
    }
}
