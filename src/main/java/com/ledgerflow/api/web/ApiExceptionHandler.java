package com.ledgerflow.api.web;

import com.ledgerflow.api.dto.ApiDtos.ErrorResponse;
import com.ledgerflow.idempotency.ConcurrentRequestException;
import com.ledgerflow.idempotency.IdempotencyKeyReuseException;
import com.ledgerflow.ledger.AccountNotFoundException;
import com.ledgerflow.ledger.AccountNotUsableException;
import com.ledgerflow.ledger.InsufficientFundsException;
import com.ledgerflow.ledger.UnbalancedTransactionException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain failures to RFC 7807 problem responses with stable {@code type}
 * URIs so clients can program against them.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private ResponseEntity<ErrorResponse> problem(HttpStatus status, String type, String title, String detail) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                "https://ledgerflow.dev/problems/" + type, title, status.value(),
                detail, MDC.get("correlationId")));
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "account-not-found", "Account not found", e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> insufficient(InsufficientFundsException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "insufficient-funds",
                "Insufficient funds", e.getMessage());
    }

    @ExceptionHandler(AccountNotUsableException.class)
    public ResponseEntity<ErrorResponse> notUsable(AccountNotUsableException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "account-not-usable",
                "Account not usable", e.getMessage());
    }

    @ExceptionHandler(UnbalancedTransactionException.class)
    public ResponseEntity<ErrorResponse> unbalanced(UnbalancedTransactionException e) {
        // Invariant violation: this is a 500-class bug, never a client error.
        log.error("LEDGER INVARIANT VIOLATION", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "ledger-invariant",
                "Ledger invariant violation", "An internal accounting invariant failed; reported.");
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    public ResponseEntity<ErrorResponse> keyReuse(IdempotencyKeyReuseException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency-key-reuse",
                "Idempotency key reused", e.getMessage());
    }

    @ExceptionHandler(ConcurrentRequestException.class)
    public ResponseEntity<ErrorResponse> concurrent(ConcurrentRequestException e) {
        return problem(HttpStatus.CONFLICT, "concurrent-request",
                "Request in progress", e.getMessage());
    }

    @ExceptionHandler(com.ledgerflow.idempotency.ReplayedFailureException.class)
    public ResponseEntity<ErrorResponse> replayedFailure(
            com.ledgerflow.idempotency.ReplayedFailureException e) {
        HttpStatus status = HttpStatus.resolve(e.getStatus());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return problem(status, "replayed-failure",
                "Original request failed", e.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            IllegalArgumentException.class})
    public ResponseEntity<ErrorResponse> badRequest(Exception e) {
        String detail = e instanceof MethodArgumentNotValidException manve
                ? manve.getBindingResult().getFieldErrors().stream()
                        .map(f -> f.getField() + ": " + f.getDefaultMessage())
                        .reduce((a, b) -> a + "; " + b).orElse("validation failed")
                : e.getMessage();
        return problem(HttpStatus.BAD_REQUEST, "bad-request", "Invalid request", detail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> unhandled(Exception e) {
        log.error("unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "Internal error", "An unexpected error occurred.");
    }

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> missingHeader(
            org.springframework.web.bind.MissingRequestHeaderException e) {
        return problem(HttpStatus.BAD_REQUEST, "missing-header",
                "Missing required header", e.getMessage());
    }
}
