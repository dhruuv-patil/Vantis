package com.flowops.security;

import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.Role;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The only supported way for a controller to obtain the tenant context.
 *
 * <p>Reading {@code organizationId} from anywhere other than {@link #require()}
 * (a request body, a query parameter) is a contract §2 violation. The single
 * exception is the switch endpoint, whose org id is a selector validated against
 * these memberships before use.
 */
public final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    /** The current principal, or {@code 401 AUTHENTICATION_REQUIRED} if unauthenticated. */
    public static FlowOpsPrincipal require() {
        return optional().orElseThrow(() -> new ApiException(ErrorCode.AUTHENTICATION_REQUIRED));
    }

    /**
     * The current principal if there is one, without failing when there is not.
     *
     * <p>For the one endpoint where a bearer token is genuinely optional: logout
     * prefers the {@code sid} of a valid token but must still succeed — and still
     * return {@code 204} — for a tab whose token expired hours ago (contract §5.4).
     */
    public static Optional<FlowOpsPrincipal> optional() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof FlowOpsPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    /** Enforces a minimum role, raising {@code 403 FORBIDDEN_ROLE} otherwise. */
    public static FlowOpsPrincipal requireRole(Role required) {
        FlowOpsPrincipal principal = require();
        if (!principal.role().atLeast(required)) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        }
        return principal;
    }

    /** Enforces platform admin privileges, raising {@code 403 FORBIDDEN_ROLE} otherwise. */
    public static FlowOpsPrincipal requirePlatformAdmin() {
        FlowOpsPrincipal principal = require();
        if (principal.role() != Role.PLATFORM_ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE, "Platform admin authorization required.");
        }
        return principal;
    }
}
