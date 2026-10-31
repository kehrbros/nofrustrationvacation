package com.vacantagent.trips.logic;

import com.vacantagent.common.errorhandling.BusinessException;

/** Input for a trip, stop or title photo violates a rule, e.g. trip ends before it starts. */
public class InvalidTripInputException extends BusinessException {
    public InvalidTripInputException(String message) {
        super(message);
    }
}
