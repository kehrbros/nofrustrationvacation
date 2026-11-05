package com.nfv.recommendations.api;

import com.nfv.network.logic.RoutingUnavailableException;
import com.nfv.recommendations.logic.InvalidSuggestionInputException;
import com.nfv.trips.logic.InvalidTripInputException;
import com.nfv.trips.logic.TripItemNotFoundException;
import com.nfv.trips.logic.TripNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Maps errors of the suggestions API to problem details, before the fallback in {@code ControllerExceptionHandler}.
 * Foreign and unknown routes look the same: 404 {@code TRIP_NOT_FOUND}.
 */
@ControllerAdvice(basePackages = "com.nfv.recommendations")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RecommendationExceptionHandler {

    @ExceptionHandler({TripNotFoundException.class, TripItemNotFoundException.class})
    public ResponseEntity<ProblemDetail> handleNotFound(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler({InvalidSuggestionInputException.class, InvalidTripInputException.class})
    public ResponseEntity<ProblemDetail> handleInvalid(RuntimeException exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_SUGGESTION_INPUT", exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable() {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "request body is not readable");
    }

    @ExceptionHandler(RoutingUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleRoutingUnavailable(RoutingUnavailableException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_UNAVAILABLE", exception.getMessage());
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}