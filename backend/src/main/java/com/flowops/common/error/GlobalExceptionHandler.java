package com.flowops.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The one place an error response body is produced for MVC-dispatched requests
 * (API contract §3). Spring Security's own 401/403 paths are covered by
 * {@link ApiErrorWriter}, so the default Spring Boot error body — whose
 * {@code error} key is a <em>string</em> and would break every client branch on
 * {@code error.code} — never reaches a client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(
            ApiException ex, HttpServletRequest request) {

        String requestId = RequestIdFilter.currentRequestId(request);
        if (ex.getCause() != null) {
            log.warn("API exception [{}] path={} requestId={} cause={}",
                    ex.code(), request.getRequestURI(), requestId, ex.getCause().getMessage());
        } else {
            log.debug("API exception [{}] path={} requestId={}",
                    ex.code(), request.getRequestURI(), requestId);
        }

        return build(ex.code(), ex.getMessage(), ex.fieldErrors(), ex.retryAfterSeconds(), ex.details(), request);
    }

    /** {@code @Valid} failure on a request body. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleBodyValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<FieldErrorDetail> fieldErrors = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.add(new FieldErrorDetail(bareField(error.getField()), message(error)));
        }

        // Class-level violations have no field to attach to, so they are surfaced
        // as the envelope message instead of being dropped.
        List<String> globalMessages = new ArrayList<>();
        for (ObjectError error : ex.getBindingResult().getGlobalErrors()) {
            globalMessages.add(message(error));
        }

        String message = globalMessages.isEmpty()
                ? ErrorCode.VALIDATION_ERROR.defaultMessage()
                : String.join(" ", globalMessages);

        return build(ErrorCode.VALIDATION_ERROR, message, fieldErrors, null, request);
    }

    /** Programmatic validation (e.g. {@code @Validated} on a service method). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {

        List<FieldErrorDetail> fieldErrors = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            fieldErrors.add(new FieldErrorDetail(
                    bareField(String.valueOf(violation.getPropertyPath())),
                    violation.getMessage()));
        }
        return build(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.defaultMessage(),
                fieldErrors, null, request);
    }

    /**
     * Unparseable JSON, a wrong JSON type, a missing body — and, because
     * {@code fail-on-unknown-properties} is on, an unknown field. The cause is
     * never echoed: it can quote the offending payload.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {

        log.debug("Malformed request body on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(ErrorCode.MALFORMED_REQUEST, null, null, null, request);
    }

    /** A path variable that could not be converted — e.g. a non-UUID org id. */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<ApiErrorResponse> handleBadArgument(
            Exception ex, HttpServletRequest request) {

        log.debug("Bad request argument on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(ErrorCode.MALFORMED_REQUEST, null, null, null, request);
    }

    /** The CSRF block for the two cookie-reading endpoints (contract §1.4). */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {

        return build(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null, null, null, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {

        return build(ErrorCode.METHOD_NOT_ALLOWED, null, null, null, request);
    }

    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            Exception ex, HttpServletRequest request) {

        return build(ErrorCode.NOT_FOUND, null, null, null, request);
    }

    /**
     * A constraint the service layer did not translate. Register's duplicate-email
     * case is caught in the service and reported as {@code EMAIL_ALREADY_REGISTERED};
     * anything else reaching here is a bug, so it is logged and reported generically.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {

        String requestId = RequestIdFilter.currentRequestId(request);
        log.error("Unhandled data integrity violation requestId={} path={}",
                requestId, request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, null, null, null, request);
    }

    /** Anything unhandled. The client gets a generic message; the log gets it all. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception ex, HttpServletRequest request) {

        String requestId = RequestIdFilter.currentRequestId(request);
        log.error("Unhandled exception requestId={} path={}", requestId, request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, null, null, null, request);
    }

    /* ------------------------------------------------------------------ util */

    private ResponseEntity<ApiErrorResponse> build(
            ErrorCode code,
            String message,
            List<FieldErrorDetail> fieldErrors,
            Long retryAfterSeconds,
            HttpServletRequest request) {
        return build(code, message, fieldErrors, retryAfterSeconds, null, request);
    }

    private ResponseEntity<ApiErrorResponse> build(
            ErrorCode code,
            String message,
            List<FieldErrorDetail> fieldErrors,
            Long retryAfterSeconds,
            PlanLimitDetail details,
            HttpServletRequest request) {

        String requestId = RequestIdFilter.currentRequestId(request);
        ApiErrorResponse body = ApiErrorResponse.of(
                code, message, request.getRequestURI(), requestId, fieldErrors, details);

        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.status())
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header(RequestIdFilter.HEADER, requestId);

        if (retryAfterSeconds != null) {
            builder = builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
        }
        return builder.body(body);
    }

    /**
     * Strips any object prefix so the wire carries {@code fullName}, never
     * {@code registerRequest.fullName} — the frontend maps errors onto inputs by
     * exact camelCase name.
     */
    private static String bareField(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int lastDot = path.lastIndexOf('.');
        return lastDot < 0 ? path : path.substring(lastDot + 1);
    }

    private static String message(ObjectError error) {
        String message = error.getDefaultMessage();
        return message == null || message.isBlank()
                ? ErrorCode.VALIDATION_ERROR.defaultMessage()
                : message;
    }
}
