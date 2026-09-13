package com.nexuspay.common.api;

import com.nexuspay.common.error.DomainException;
import com.nexuspay.common.error.EntityNotFoundException;
import com.nexuspay.common.error.ErrorCode;
import com.nexuspay.common.money.CurrencyMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Translates exceptions into RFC 9457 problem responses.
 * <p>
 * This is the only place in the codebase that knows a business failure maps
 * onto an HTTP status. The domain throws {@link DomainException} subtypes that
 * carry a stable {@link ErrorCode} and no web types at all; the translation
 * happens here, at the boundary.
 * <p>
 * Every response carries the correlation ID, so a user reporting "my payment
 * failed" hands over one string that finds the request across every system.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail handleNotFound(EntityNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.errorCode(), ex.getMessage());
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(CurrencyMismatchException ex) {
        return problem(HttpStatus.BAD_REQUEST, ex.errorCode(), ex.getMessage());
    }

    /**
     * 422 rather than 400: the request was syntactically fine and we understood
     * it perfectly — the business rules simply do not allow it. Refunding more
     * than was captured is not a malformed request.
     */
    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomain(DomainException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.errorCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> violations = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(f -> f.getField()))
                .map(f -> Map.of(
                        "field", f.getField(),
                        "message", f.getDefaultMessage() == null ? "is invalid" : f.getDefaultMessage()))
                .toList();

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "the request failed validation");
        problem.setProperty("violations", violations);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        // Deliberately not echoing the parser's message: it can quote the raw
        // request body, which may contain card data.
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "the request body could not be parsed");
    }

    /**
     * Anything unhandled is a fault, not a business outcome. The client gets a
     * correlation ID and nothing else — stack traces and exception messages can
     * leak internals, and in this domain that could mean card data.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("unhandled exception [correlationId={}]", CorrelationIdFilter.current(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "an internal error occurred");
    }

    private static ProblemDetail problem(HttpStatus status, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("errorCode", code.name());

        String correlationId = CorrelationIdFilter.current();
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }
}
