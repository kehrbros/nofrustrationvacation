package com.nfv.bookings.api;

import com.nfv.assistant.logic.AssistantRateLimitedException;
import com.nfv.assistant.logic.AssistantUnavailableException;
import com.nfv.bookings.logic.BookingNotFoundException;
import com.nfv.bookings.logic.InvalidBookingInputException;
import com.nfv.trips.logic.TripNotFoundException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Maps booking errors to problem details, before the fallback in {@code ControllerExceptionHandler}. */
@ControllerAdvice(basePackages = "com.nfv.bookings")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class BookingExceptionHandler {

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(BookingNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(TripNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleTripNotFound(TripNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(InvalidBookingInputException.class)
    public ResponseEntity<ProblemDetail> handleInvalid(InvalidBookingInputException exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_BOOKING_INPUT", exception.getMessage());
    }

    @ExceptionHandler(AssistantUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleUnavailable(AssistantUnavailableException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_UNAVAILABLE", exception.getMessage());
    }

    @ExceptionHandler(AssistantRateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(AssistantRateLimitedException exception) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "ASSISTANT_RATE_LIMITED", exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleTooLarge() {
        return problem(HttpStatus.CONTENT_TOO_LARGE, "UPLOAD_TOO_LARGE", "upload too large");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable() {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "request body is not readable");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
