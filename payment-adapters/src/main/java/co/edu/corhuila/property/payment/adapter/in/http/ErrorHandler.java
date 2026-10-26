package co.edu.corhuila.property.payment.adapter.in.http;

import co.edu.corhuila.property.payment.adapter.in.http.ApiError.ValidationException;
import co.edu.corhuila.property.payment.domain.model.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The ONLY place where errors become status codes (Annex C). Every error answers
 * with the envelope, unknown routes and malformed JSON included. The domain
 * throws typed errors and does not know what a 422 is.
 */
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ValidationException.class)
    ResponseEntity<ApiError> validation(ValidationException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of(ApiError.VALIDATION_ERROR, e.getMessage(), e.details()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.of(ApiError.VALIDATION_ERROR, "the body is not valid JSON"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute(NoResourceFoundException e) {
        return respond(HttpStatus.NOT_FOUND, ApiError.of(ApiError.NOT_FOUND, "the route does not exist"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ApiError.of(ApiError.NOT_FOUND, "method not allowed on this route"));
    }

    /** A Pago and a Reembolso have no transitions: every domain rule is a business rule (INV-004 to INV-006). */
    @ExceptionHandler(DomainException.class)
    ResponseEntity<ApiError> businessRule(DomainException e) {
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, ApiError.of(ApiError.BUSINESS_RULE_VIOLATION, e.getMessage()));
    }

    /** Logged in full (the MDC adds the correlation id); the client gets a neutral text. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("unhandled error", e);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of(ApiError.INTERNAL_ERROR, "internal server error"));
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, ApiError body) {
        return ResponseEntity.status(status).body(body);
    }
}
