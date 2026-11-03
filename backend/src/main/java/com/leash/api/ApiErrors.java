package com.leash.api;

import com.leash.paypal.PayPalException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/** Every error leaves as problem-JSON (RFC 9457) with a sentence a person can act on. */
@RestControllerAdvice
public class ApiErrors {

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail notFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    ProblemDetail forbidden(ForbiddenException e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage());
    }

    @ExceptionHandler(NotConfiguredException.class)
    ProblemDetail notConfigured(NotConfiguredException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Not configured", e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class})
    ProblemDetail badRequest(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail conflict(IllegalStateException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", detail);
    }

    /** PayPalException messages are built to be safe to show: status, error name, debug_id. No bodies. */
    @ExceptionHandler(PayPalException.class)
    ProblemDetail paypal(PayPalException e) {
        ProblemDetail p = problem(HttpStatus.BAD_GATEWAY, "PayPal error", e.getMessage());
        if (e.errorName() != null) p.setProperty("paypalError", e.errorName());
        return p;
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail == null ? title : detail);
        p.setTitle(title);
        return p;
    }
}
