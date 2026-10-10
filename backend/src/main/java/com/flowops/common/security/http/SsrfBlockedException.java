package com.flowops.common.security.http;

import com.flowops.common.error.ErrorCode;

/**
 * Thrown when an outbound destination violates SSRF policies (e.g. private IP, loopback,
 * cloud metadata, disallowed port, or non-canonical hostname).
 */
public class SsrfBlockedException extends HardenedHttpException {

    public SsrfBlockedException(String safeMessage) {
        super(ErrorCode.INTEGRATION_INVALID, safeMessage);
    }

    public SsrfBlockedException(String safeMessage, Throwable cause) {
        super(ErrorCode.INTEGRATION_INVALID, safeMessage, cause);
    }
}
