package com.nfv.trips.logic;

import com.nfv.common.errorhandling.BusinessException;

/** Input for a trip, stop or title photo violates a rule, e.g. trip ends before it starts. */
public class InvalidTripInputException extends BusinessException {
    public InvalidTripInputException(String message) {
        super(message);
    }
}
