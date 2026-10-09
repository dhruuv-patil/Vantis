package com.flowops.common.security.http;

import com.flowops.common.error.ErrorCode;

/**
 * Thrown when outbound HTTP execution exceeds the maximum permitted redirect hops.
 */
public class TooManyRedirectsException extends HardenedHttpException {

    public TooManyRedirectsException(int maxHops) {
        super(ErrorCode.INTEGRATION_INVALID, "Exceeded maximum allowed redirect hops (" + maxHops + ")");
    }
}
