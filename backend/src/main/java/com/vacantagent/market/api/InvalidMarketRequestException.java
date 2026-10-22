package com.vacantagent.market.api;

import com.vacantagent.common.errorhandling.BusinessException;

/** An order or offer the API refuses before it reaches the broker. */
public class InvalidMarketRequestException extends BusinessException {
    public InvalidMarketRequestException(String message) {
        super(message);
    }
}