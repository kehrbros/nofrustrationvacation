package com.vacantagent.bookings.logic;

import com.vacantagent.common.errorhandling.BusinessException;

public class InvalidBookingInputException extends BusinessException {
    public InvalidBookingInputException(String message) {
        super(message);
    }
}
