package dev.tkachuk.gateway.common.web;

import dev.tkachuk.gateway.common.error.ErrorCode;
import dev.tkachuk.gateway.common.error.GatewayException;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps exceptions to problem details with a "code": the one place ErrorCode meets HTTP. Validation errors name the
 * field and the rule, never the rejected value, which could be a card number.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(GatewayException.class)
    ResponseEntity<ProblemDetail> handleGatewayException(GatewayException ex) {
        HttpStatusCode status = statusOf(ex.code());
        return ResponseEntity.status(status).body(Problems.of(status, ex.code().name(), ex.getMessage()));
    }

    // Constraints on the headers of the generated, @Validated API interface.
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> violations = ex.getConstraintViolations().stream()
                .map(violation -> leafName(violation.getPropertyPath()) + ": " + violation.getMessage())
                .sorted()
                .toList();
        return validationFailed(HttpStatusCode.valueOf(400), new HttpHeaders(), "The request is invalid", violations);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();
        return validationFailed(status, headers, "The request body is invalid", violations);
    }

    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> violations = ex instanceof MissingRequestHeaderException missing
                ? List.of(missing.getHeaderName() + ": is required")
                : List.of();
        return validationFailed(status, headers, "The request is invalid", violations);
    }

    // Jackson's own message is not passed on: it can quote the request body.
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validationFailed(status, headers, "The request body is not valid JSON, or a value has the wrong type",
                List.of());
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return validationFailed(status, headers, "The request is invalid",
                List.of(ex.getPropertyName() + ": has the wrong format"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        HttpStatusCode status = HttpStatusCode.valueOf(500);
        return ResponseEntity.status(status).body(Problems.of(status, INTERNAL_ERROR, "Unexpected error"));
    }

    static HttpStatusCode statusOf(ErrorCode code) {
        int status = switch (code) {
            case PAYMENT_NOT_FOUND -> 404;
            // Well-formed requests that can never succeed as sent. A reused key is 422 by the IETF
            // Idempotency-Key draft, not 409: retrying it won't help.
            case CARD_INVALID, CARD_EXPIRED, RETURN_URL_INVALID, IDEMPOTENCY_KEY_REUSED -> 422;
        };
        return HttpStatusCode.valueOf(status);
    }

    private static ResponseEntity<Object> validationFailed(HttpStatusCode status, HttpHeaders headers, String detail,
            List<String> violations) {
        ProblemDetail problem = Problems.of(status, VALIDATION_FAILED, detail);
        if (!violations.isEmpty()) {
            problem.setProperty("violations", violations);
        }
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    private static String leafName(Path path) {
        String name = path.toString();
        for (Path.Node node : path) {
            if (node.getName() != null) {
                name = node.getName();
            }
        }
        return name;
    }
}
