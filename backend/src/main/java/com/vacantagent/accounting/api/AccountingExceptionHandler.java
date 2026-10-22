package com.vacantagent.accounting.api;

import com.vacantagent.accounting.logic.AccountingException;
import com.vacantagent.accounting.logic.AmountOutOfRangeException;
import com.vacantagent.accounting.logic.InsufficientBalanceException;
import com.vacantagent.accounting.logic.PaymentDeclinedException;
import com.vacantagent.accounting.logic.PayoutAccountLinkException;
import com.vacantagent.accounting.logic.PayoutAccountRequiredException;
import com.vacantagent.accounting.logic.TopUpNotFoundException;
import com.vacantagent.currency.logic.ExchangeRateUnavailableException;
import com.vacantagent.paypal.PayPalNotConfiguredException;
import com.vacantagent.paypal.PayPalRejectedException;
import com.vacantagent.paypal.PayPalUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Maps accounting and PayPal errors to problem details with a stable {@code code} for the frontend. Runs before
 * the fallback in {@code ControllerExceptionHandler}, which maps everything else to 503.
 */
@Slf4j
@ControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AccountingExceptionHandler {

    @ExceptionHandler(AccountingException.class)
    public ResponseEntity<ProblemDetail> handleAccounting(AccountingException exception) {
        HttpStatus status = switch (exception) {
            case InvalidAmountException _ -> HttpStatus.BAD_REQUEST;
            case InsufficientBalanceException _, PayoutAccountRequiredException _ -> HttpStatus.CONFLICT;
            case AmountOutOfRangeException _, PaymentDeclinedException _ -> HttpStatus.UNPROCESSABLE_CONTENT;
            case TopUpNotFoundException _ -> HttpStatus.NOT_FOUND;
            case PayoutAccountLinkException link when PayoutAccountLinkException.INVALID_STATE.equals(link.getCode()) ->
                    HttpStatus.BAD_REQUEST;
            case PayoutAccountLinkException _ -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        return problem(status, exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(PayPalNotConfiguredException.class)
    public ResponseEntity<ProblemDetail> handlePayPalNotConfigured(PayPalNotConfiguredException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "PAYPAL_NOT_CONFIGURED", "PayPal is not available");
    }

    @ExceptionHandler({PayPalRejectedException.class, PayPalUnavailableException.class})
    public ResponseEntity<ProblemDetail> handlePayPal(RuntimeException exception) {
        log.warn("PayPal call failed", exception);
        return problem(HttpStatus.BAD_GATEWAY, "PAYPAL_ERROR", "PayPal could not process the request");
    }

    @ExceptionHandler(ExchangeRateUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleExchangeRate(ExchangeRateUnavailableException exception) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "EXCHANGE_RATE_UNAVAILABLE", "No current exchange rate for this currency");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
