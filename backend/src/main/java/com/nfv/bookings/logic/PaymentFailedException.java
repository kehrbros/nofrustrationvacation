package com.nfv.bookings.logic;

import com.nfv.common.errorhandling.BusinessException;
import lombok.Getter;

/** The payment at PayPal was not approved or was declined; nothing was booked. */
@Getter
public class PaymentFailedException extends BusinessException {
    private final String code;

    public PaymentFailedException(String code, String message) {
        super(message);
        this.code = code;
    }
}
