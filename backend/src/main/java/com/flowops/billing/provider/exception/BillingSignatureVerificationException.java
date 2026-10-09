package com.flowops.billing.provider.exception;

/**
 * Thrown when an inbound billing webhook fails cryptographic signature verification.
 */
public class BillingSignatureVerificationException extends RuntimeException {

    private final String errorCode;

    public BillingSignatureVerificationException(String message) {
        super(message);
        this.errorCode = "INVALID_SIGNATURE";
    }

    public BillingSignatureVerificationException(String message, String errorCode) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
