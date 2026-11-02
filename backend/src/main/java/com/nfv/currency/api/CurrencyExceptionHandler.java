package com.nfv.currency.api;

import com.nfv.currency.logic.UnsupportedCurrencyException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Runs before the fallback in {@code ControllerExceptionHandler}, which maps everything else to 503.
 */
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CurrencyExceptionHandler {

    @ExceptionHandler(UnsupportedCurrencyException.class)
    public ResponseEntity<Void> handleUnsupportedCurrency() {
        return ResponseEntity.badRequest().build();
    }
}
