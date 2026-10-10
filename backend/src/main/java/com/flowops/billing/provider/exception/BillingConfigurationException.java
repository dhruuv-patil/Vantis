package com.flowops.billing.provider.exception;

/**
 * Thrown during startup or provider initialization when required billing configuration
 * (e.g. price IDs, secret keys, webhook secrets) is missing or invalid.
 */
public class BillingConfigurationException extends IllegalStateException {

    public BillingConfigurationException(String message) {
        super(message);
    }

    public BillingConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
