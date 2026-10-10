package com.flowops.common.security.http;

import com.flowops.common.error.ErrorCode;

/**
 * Thrown when an outbound response exceeds the configured maximum response byte cap.
 */
public class PayloadTooLargeException extends HardenedHttpException {

    public PayloadTooLargeException(long maxBytes) {
        super(ErrorCode.INTEGRATION_INVALID, "Remote response exceeded maximum permitted size limit (" + maxBytes + " bytes)");
    }
}
