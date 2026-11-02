package com.nfv.bookings.logic;

import com.nfv.common.errorhandling.BusinessException;

public class InvalidBookingInputException extends BusinessException {
    public InvalidBookingInputException(String message) {
        super(message);
    }
}
