package com.nfv.trips.api;

import com.nfv.assistant.logic.AssistantRateLimitedException;
import com.nfv.assistant.logic.AssistantUnavailableException;
import com.nfv.trips.logic.InvalidTripInputException;
import com.nfv.trips.logic.LocationOutsideRegionsException;
import com.nfv.trips.logic.StopBookingsConflictException;
import com.nfv.trips.logic.StopInUseException;
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
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/**
 * Maps trip errors to problem details. Runs before the fallback in {@code ControllerExceptionHandler}, which maps
 * everything else to 503. Applies to the trips and incidents API (the latter looks up trips through {@code TripLogic}).
 */
@ControllerAdvice(basePackages = {"com.nfv.trips", "com.nfv.incidents"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TripExceptionHandler {

    @ExceptionHandler(TripNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(TripNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(TripItemNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleItemNotFound(TripItemNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.code(), exception.getMessage());
    }

    @ExceptionHandler(InvalidTripInputException.class)
    public ResponseEntity<ProblemDetail> handleInvalid(InvalidTripInputException exception) {
        String code = exception instanceof LocationOutsideRegionsException ? "LOCATION_OUTSIDE_REGIONS" : "INVALID_TRIP_INPUT";
        return problem(HttpStatus.BAD_REQUEST, code, exception.getMessage());
    }

    @ExceptionHandler(StopInUseException.class)
    public ResponseEntity<ProblemDetail> handleStopInUse(StopInUseException exception) {
        return problem(HttpStatus.CONFLICT, "STOP_IN_USE", exception.getMessage());
    }

    /** Bookings outside the new days of the stop: the frontend offers to extend the stop or shorten the bookings. */
    @ExceptionHandler(StopBookingsConflictException.class)
    public ResponseEntity<ProblemDetail> handleBookingsConflict(StopBookingsConflictException exception) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.CONFLICT, "STOP_BOOKINGS_CONFLICT",
                exception.getMessage());
        ProblemDetail problem = response.getBody();
        problem.setProperty("bookings", exception.getBookings());
        problem.setProperty("extendedArrivalAt", exception.getExtendedArrivalAt());
        problem.setProperty("extendedDepartureAt", exception.getExtendedDepartureAt());
        return response;
    }

    @ExceptionHandler(AssistantUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleAssistantUnavailable(AssistantUnavailableException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "ASSISTANT_UNAVAILABLE", exception.getMessage());
    }

    @ExceptionHandler(AssistantRateLimitedException.class)
    public ResponseEntity<ProblemDetail> handleAssistantRateLimited(AssistantRateLimitedException exception) {
        return problem(HttpStatus.TOO_MANY_REQUESTS, "ASSISTANT_RATE_LIMITED", exception.getMessage());
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
