package com.nfv.common.errorhandling;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@Slf4j
@ControllerAdvice
public class ControllerExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Void> handleExceptionFallback(Exception exception) throws Exception {
        if (exception instanceof ErrorResponse) {
            // Spring's own request errors (unknown path, wrong method, missing parameter, ...) keep their status
            // (404, 405, 400, ...) and, in the browser, show the error page instead of an empty 503
            throw exception;
        }
        log.error("Controller method handling failed.", exception);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
}
