package com.nfv.advisor.api;

import com.nfv.advisor.logic.InvalidProposalInputException;
import com.nfv.bookings.logic.BookingNotFoundException;
import com.nfv.bookings.logic.InvalidBookingInputException;
import com.nfv.bookings.logic.LiteApiUnavailableException;
import com.nfv.bookings.logic.OfferUnavailableException;
import com.nfv.bookings.logic.PaymentFailedException;
import com.nfv.bookings.logic.PriceExceededException;
import com.nfv.bookings.logic.ReservationNotFoundException;
import com.nfv.advisor.logic.NoActiveConsequenceException;
import com.nfv.advisor.logic.ProposalNotApplicableException;

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
 * Maps errors of the proposals API to problem details, before the fallback in {@code ControllerExceptionHandler}.
 * Foreign and unknown routes and runs look the same: 404 {@code TRIP_NOT_FOUND}.
 */
@ControllerAdvice(basePackages = "com.nfv.advisor")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AdvisorExceptionHandler {

    @ExceptionHandler({TripNotFoundException.class, TripItemNotFoundException.class})
    public ResponseEntity<ProblemDetail> handleNotFound(RuntimeException exception) {
        return problem(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(NoActiveConsequenceException.class)
    public ResponseEntity<ProblemDetail> handleNoConsequence(NoActiveConsequenceException exception) {
        return problem(HttpStatus.CONFLICT, "NO_ACTIVE_CONSEQUENCE", exception.getMessage());
    }

    @ExceptionHandler(InvalidProposalInputException.class)
    public ResponseEntity<ProblemDetail> handleInvalid(InvalidProposalInputException exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_PROPOSAL_INPUT", exception.getMessage());
    }

    @ExceptionHandler(ProposalNotApplicableException.class)
    public ResponseEntity<ProblemDetail> handleNotApplicable(ProposalNotApplicableException exception) {
        return problem(HttpStatus.CONFLICT, "PROPOSAL_NOT_APPLICABLE", exception.getMessage());
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleReservationNotFound(ReservationNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "RESERVATION_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(BookingNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleBookingNotFound(BookingNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(InvalidBookingInputException.class)
    public ResponseEntity<ProblemDetail> handleInvalidBooking(InvalidBookingInputException exception) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.BAD_REQUEST, "INVALID_BOOKING_INPUT",
                exception.getMessage());
        if (exception.getField() != null) {
            // the frontend marks the guest field
            response.getBody().setProperty("field", exception.getField());
        }
        return response;
    }

    @ExceptionHandler(OfferUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleOfferUnavailable(OfferUnavailableException exception) {
        return problem(HttpStatus.CONFLICT, "OFFER_UNAVAILABLE", exception.getMessage());
    }

    @ExceptionHandler(PriceExceededException.class)
    public ResponseEntity<ProblemDetail> handlePriceExceeded(PriceExceededException exception) {
        return problem(HttpStatus.CONFLICT, "PRICE_EXCEEDED", exception.getMessage());
    }

    @ExceptionHandler(PaymentFailedException.class)
    public ResponseEntity<ProblemDetail> handlePaymentFailed(PaymentFailedException exception) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(LiteApiUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleLiteApi(LiteApiUnavailableException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "ALTERNATIVES_UNAVAILABLE", exception.getMessage());
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
