package com.vacantagent.market.api;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Runs before the fallback in {@code ControllerExceptionHandler}, which maps everything else to 503.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MarketExceptionHandler {

    @ExceptionHandler(InvalidMarketRequestException.class)
    public ResponseEntity<Void> handleInvalidMarketRequest() {
        return ResponseEntity.badRequest().build();
    }
}