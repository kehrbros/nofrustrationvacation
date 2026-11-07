package com.nfv.advisor.api;

import com.nfv.advisor.logic.InvalidProposalInputException;
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
