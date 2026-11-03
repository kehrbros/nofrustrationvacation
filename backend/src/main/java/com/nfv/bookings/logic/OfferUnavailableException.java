package com.nfv.bookings.logic;

import com.nfv.common.errorhandling.BusinessException;
import lombok.Getter;

/** LiteAPI refused prebook or book (offer gone, price changed, invalid input); nothing was booked. */
@Getter
public class OfferUnavailableException extends BusinessException {
    /** LiteAPI error code, e.g. {@code 4002}; {@code 4005} means a booking with our reference exists already. */
    private final String providerCode;

    public OfferUnavailableException(String providerCode, String description) {
        super("offer no longer available (LiteAPI " + providerCode + ")" + (description.isBlank() ? "" : ": " + description));
        this.providerCode = providerCode;
    }
}
