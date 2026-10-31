package com.vacantagent.trips.api;

import com.vacantagent.trips.logic.InvalidTripInputException;
import com.vacantagent.trips.logic.TripNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Maps trip errors to problem details. Runs before the fallback in {@code ControllerExceptionHandler}, which maps
 * everything else to 503. Applies to the trips and incidents API (the latter looks up trips through {@code TripLogic}).
 */
@ControllerAdvice(basePackages = {"com.vacantagent.trips", "com.vacantagent.incidents"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TripExceptionHandler {

    @ExceptionHandler(TripNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(TripNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(InvalidTripInputException.class)
    public ResponseEntity<ProblemDetail> handleInvalid(InvalidTripInputException exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_TRIP_INPUT", exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleTooLarge() {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "UPLOAD_TOO_LARGE", "upload too large");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ProblemDetail> handleUnreadable() {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "request body is not readable");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
