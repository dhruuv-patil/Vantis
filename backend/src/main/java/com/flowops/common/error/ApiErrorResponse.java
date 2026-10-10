package com.flowops.common.error;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The one error envelope every non-2xx response uses (API contract §3).
 *
 * <p>{@code fieldErrors} is an array only for {@link ErrorCode#VALIDATION_ERROR}
 * and {@code null} otherwise — but the key is <em>always</em> serialized, which is
 * why no {@code @JsonInclude(NON_NULL)} appears here or in the global Jackson
 * config. The frontend parser depends on the key existing.
 */
public record ApiErrorResponse(Body error) {

    public record Body(
            String code,
            String message,
            int status,
            Instant timestamp,
            String path,
            String requestId,
            List<FieldErrorDetail> fieldErrors,
            PlanLimitDetail details) {
    }

    public static ApiErrorResponse of(
            ErrorCode code,
            String message,
            String path,
            String requestId,
            List<FieldErrorDetail> fieldErrors,
            PlanLimitDetail details) {

        return new ApiErrorResponse(new Body(
                code.name(),
                message == null || message.isBlank() ? code.defaultMessage() : message,
                code.status().value(),
                // Truncated so the serialized form is ISO-8601 with milliseconds,
                // as the contract specifies, rather than microseconds.
                Instant.now().truncatedTo(ChronoUnit.MILLIS),
                path,
                requestId,
                fieldErrors,
                details));
    }

    public static ApiErrorResponse of(
            ErrorCode code,
            String message,
            String path,
            String requestId,
            List<FieldErrorDetail> fieldErrors) {

        return of(code, message, path, requestId, fieldErrors, null);
    }
}
