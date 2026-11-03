package com.nfv.bookings.logic;

import com.nfv.common.errorhandling.BusinessException;

/** The user searched for alternatives too often within the last hour. */
public class SearchRateLimitedException extends BusinessException {
    public SearchRateLimitedException() {
        super("too many searches for alternatives, try again later");
    }
}
