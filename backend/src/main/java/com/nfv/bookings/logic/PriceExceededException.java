package com.nfv.bookings.logic;

import com.nfv.common.errorhandling.BusinessException;

/** The price locked at prebook is above the maximum price of the criteria; nothing was paid or booked. */
public class PriceExceededException extends BusinessException {
    public PriceExceededException(String price, String maxPrice) {
        super("price " + price + " is above the maximum " + maxPrice);
    }
}
