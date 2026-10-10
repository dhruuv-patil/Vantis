package com.flowops.billing.provider.exception;

import java.util.Collections;
import java.util.Map;

/**
 * Structured validation exception for billing provider requests.
 * Carries error code and contextual attributes (e.g. currency, plan, supported currencies).
 */
public class BillingValidationException extends RuntimeException {

    private final String errorCode;
    private final Map<String, Object> details;

    public BillingValidationException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
        this.details = Collections.emptyMap();
    }

    public BillingValidationException(String message, String errorCode, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details != null ? Collections.unmodifiableMap(details) : Collections.emptyMap();
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }
}
