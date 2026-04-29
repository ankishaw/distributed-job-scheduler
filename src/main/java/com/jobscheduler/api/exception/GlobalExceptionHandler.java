package com.jobscheduler.api.exception;

import com.jobscheduler.api.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Centralised exception → HTTP response mapping.
 *
 * Every error response goes through here so the client always
 * gets a consistent ErrorResponse shape.
 *
 * Mappings:
 *   JobNotFoundException        → 404 Not Found
 *   MethodArgumentNotValidException → 400 Bad Request (with field errors)
 *   IllegalStateException       → 409 Conflict (invalid state transition)
 *   IllegalArgumentException    → 400 Bad Request
 *   Exception (catch-all)       → 500 Internal Server Error
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── 404 — Job not found ───────────────────────────────────────────────────

    @ExceptionHandler(JobNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleJobNotFound(JobNotFoundException ex,
                                           HttpServletRequest req) {
        return new ErrorResponse(
            HttpStatus.NOT_FOUND.value(),
            "Not Found",
            ex.getMessage(),
            req.getRequestURI()
        );
    }

    // ── 400 — Bean Validation failures (@Valid) ───────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex,
                                          HttpServletRequest req) {
        BindingResult br = ex.getBindingResult();

        List<ErrorResponse.FieldError> fieldErrors = br.getFieldErrors()
            .stream()
            .map(fe -> new ErrorResponse.FieldError(
                fe.getField(),
                fe.getDefaultMessage()
            ))
            .toList();

        ErrorResponse resp = new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            "Bad Request",
            "Validation failed — check fieldErrors for details",
            req.getRequestURI()
        );
        resp.setFieldErrors(fieldErrors);
        return resp;
    }

    // ── 409 — Invalid job state transition ───────────────────────────────────

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleIllegalState(IllegalStateException ex,
                                            HttpServletRequest req) {
        return new ErrorResponse(
            HttpStatus.CONFLICT.value(),
            "Conflict",
            ex.getMessage(),
            req.getRequestURI()
        );
    }

    // ── 400 — Bad argument ────────────────────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleIllegalArgument(IllegalArgumentException ex,
                                               HttpServletRequest req) {
        return new ErrorResponse(
            HttpStatus.BAD_REQUEST.value(),
            "Bad Request",
            ex.getMessage(),
            req.getRequestURI()
        );
    }

    // ── 500 — Catch-all ───────────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneric(Exception ex, HttpServletRequest req) {
        return new ErrorResponse(
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "Internal Server Error",
            "An unexpected error occurred",
            req.getRequestURI()
        );
    }
}
