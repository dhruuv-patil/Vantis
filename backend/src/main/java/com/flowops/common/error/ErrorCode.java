package com.flowops.common.error;

import org.springframework.http.HttpStatus;

/**
 * The exhaustive M1 error catalogue (API contract §3.1).
 *
 * <p>Clients branch on the code, never on the message, so these names are part of
 * the wire contract: renaming one is a breaking change. The mirrored TypeScript
 * union lives in {@code frontend/types/index.ts}.
 *
 * <p>Messages are safe to show to end users: no stack trace, SQL, class name,
 * email address, token or password ever appears in one.
 */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed."),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "The request body could not be read."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            "Content-Type must be application/json."),
    /** The request body exceeded the endpoint's size cap (e.g. an inbound webhook payload). */
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "The request body is too large."),

    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication is required."),
    /** Identical for an unknown email and a wrong password — see contract §3.2. */
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Incorrect email or password."),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Access token has expired."),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Access token is invalid."),
    REFRESH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED,
            "Your session has expired. Please sign in again."),
    RESET_TOKEN_INVALID(HttpStatus.UNAUTHORIZED,
            "The reset link is invalid or has expired. Please request a new one."),
    VERIFICATION_TOKEN_INVALID(HttpStatus.BAD_REQUEST,
            "The verification link is invalid or has expired. Please request a new one."),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN,
            "Please verify your email address to continue."),

    FORBIDDEN_ROLE(HttpStatus.FORBIDDEN, "You do not have permission to do that."),
    NO_ORGANIZATION_CONTEXT(HttpStatus.FORBIDDEN, "You do not belong to any organization."),

    /** Returned both for a nonexistent org and for a non-member — §2.1. */
    ORGANIZATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Organization not found."),
    /** Returned for a nonexistent membership and for one in another org (anti-enumeration). */
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "Member not found."),
    /** You cannot change or remove your own membership through the team endpoints. */
    CANNOT_MODIFY_SELF(HttpStatus.CONFLICT, "You cannot change your own membership here."),
    /** The last owner cannot be demoted or removed — an org must always have one. */
    LAST_OWNER(HttpStatus.CONFLICT, "An organization must always have at least one owner."),
    /** Returned for a nonexistent, expired, revoked, or non-matching invitation. */
    INVITATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Invitation not found."),
    /** The invitation request is not valid (bad role, already a member, or bad email). */
    INVITATION_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "That invitation is not valid."),
    /** The supplied current password did not match — used only by change-password. */
    INVALID_PASSWORD(HttpStatus.UNAUTHORIZED, "Current password is incorrect."),
    /** Returned for a nonexistent workflow and for one owned by another org. */
    WORKFLOW_NOT_FOUND(HttpStatus.NOT_FOUND, "Workflow not found."),
    /** A publish (or other transition) was blocked by graph validation errors. */
    WORKFLOW_INVALID(HttpStatus.UNPROCESSABLE_ENTITY,
            "The workflow has validation errors and cannot be published."),
    /** A run was requested for a workflow that has no published version yet. */
    WORKFLOW_NOT_PUBLISHED(HttpStatus.UNPROCESSABLE_ENTITY,
            "Publish a version of this workflow before running it."),
    /** No built-in template has the requested slug. */
    TEMPLATE_NOT_FOUND(HttpStatus.NOT_FOUND, "Template not found."),
    /** Returned for a nonexistent execution and for one owned by another org. */
    EXECUTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Execution not found."),
    /** Retry was requested for an execution that is not in a failed state. */
    EXECUTION_NOT_RETRYABLE(HttpStatus.CONFLICT,
            "Only a failed execution can be retried."),
    /** Cancel was requested for a run that is not currently in flight
     *  (e.g. already terminal, or waiting on a human decision). */
    EXECUTION_NOT_CANCELABLE(HttpStatus.CONFLICT,
            "This run cannot be canceled in its current state."),
    /** An approval decision was submitted for a node that is not awaiting one. */
    APPROVAL_NOT_PENDING(HttpStatus.CONFLICT,
            "This step is not awaiting an approval decision."),

    /** Returned for a nonexistent AI agent and for one owned by another org. */
    AI_AGENT_NOT_FOUND(HttpStatus.NOT_FOUND, "AI agent not found."),
    /** An AI feature was invoked while no provider key is configured on the AI service. */
    AI_NOT_CONFIGURED(HttpStatus.SERVICE_UNAVAILABLE,
            "AI is not configured. Set an AI provider key to enable this feature."),
    /** The AI service was unreachable or returned an error. */
    AI_SERVICE_ERROR(HttpStatus.BAD_GATEWAY,
            "The AI service is currently unavailable. Please try again."),
    /** The model returned nothing usable as a workflow. */
    AI_GENERATION_FAILED(HttpStatus.UNPROCESSABLE_ENTITY,
            "The AI could not turn that into a workflow. Try rephrasing your prompt."),

    /** Returned for a nonexistent integration and for one owned by another org. */
    INTEGRATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Integration not found."),
    /** The submitted credential or connection configuration is not valid. */
    INTEGRATION_INVALID(HttpStatus.UNPROCESSABLE_ENTITY,
            "The integration credentials or configuration are invalid."),

    /** Returned for a nonexistent notification and for one addressed to another user. */
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "Notification not found."),

    /** Returned for a nonexistent anomaly and for one owned by another org (anti-enumeration). */
    ANOMALY_NOT_FOUND(HttpStatus.NOT_FOUND, "Anomaly not found."),
    ANOMALY_ALREADY_CLOSED(HttpStatus.CONFLICT, "This anomaly has already been closed."),

    NOT_FOUND(HttpStatus.NOT_FOUND, "The requested resource was not found."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "That method is not supported for this path."),

    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "That email address is already registered."),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Please try again later."),
    CONCURRENCY_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Concurrent execution limit reached. Please wait for active runs to finish."),
    CIRCUIT_BREAKER_OPEN(HttpStatus.SERVICE_UNAVAILABLE, "Dependency circuit breaker is open. Requests temporarily paused."),
    DEAD_LETTER_NOT_FOUND(HttpStatus.NOT_FOUND, "Dead letter record not found."),
    INCIDENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Incident not found."),
    PLAN_LIMIT_EXCEEDED(HttpStatus.FORBIDDEN, "Plan limit reached for this billing period."),
    BILLING_DISABLED(HttpStatus.FORBIDDEN, "Billing will be available after the private beta."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again.");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /**
     * Best-effort mapping used only by the {@code /error} fallback, which sees a
     * status code with no exception attached.
     */
    public static ErrorCode fromStatus(int status) {
        return switch (status) {
            case 400 -> MALFORMED_REQUEST;
            case 401 -> AUTHENTICATION_REQUIRED;
            case 403 -> FORBIDDEN_ROLE;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> RATE_LIMITED;
            default -> status >= 400 && status < 500 ? NOT_FOUND : INTERNAL_ERROR;
        };
    }
}
