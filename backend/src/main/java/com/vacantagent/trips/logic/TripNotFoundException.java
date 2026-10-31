package com.vacantagent.trips.logic;

import com.vacantagent.common.errorhandling.BusinessException;

/** The trip does not exist or belongs to another user, both look the same to the caller. */
public class TripNotFoundException extends BusinessException {
    public TripNotFoundException(long tripId) {
        super("trip " + tripId + " not found");
    }
}
