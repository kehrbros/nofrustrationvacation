package com.nfv.incidents.api;

import com.nfv.incidents.logic.IncidentNotResolvedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** Errors of the incidents API; trip errors are mapped by {@code TripExceptionHandler}. */
@ControllerAdvice(basePackages = "com.nfv.incidents")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IncidentExceptionHandler {

    @ExceptionHandler(IncidentNotResolvedException.class)
    public ResponseEntity<ProblemDetail> handleNotResolved(IncidentNotResolvedException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setProperty("code", "INCIDENT_NOT_RESOLVED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
