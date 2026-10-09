package com.flowops.common.security.http;

import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;

/**
 * Base exception for hardened HTTP client failures.
 * Never echoes internal addresses, credentials, or malicious payload data in the message.
 */
public class HardenedHttpException extends RuntimeException {

    private final ErrorCode errorCode;

    public HardenedHttpException(ErrorCode errorCode, String safeMessage) {
        super(safeMessage);
        this.errorCode = errorCode;
    }

    public HardenedHttpException(ErrorCode errorCode, String safeMessage, Throwable cause) {
        super(safeMessage, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public ApiException toApiException() {
        return new ApiException(errorCode, getMessage());
    }
}
