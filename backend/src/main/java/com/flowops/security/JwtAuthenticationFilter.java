package com.flowops.security;

import com.flowops.common.error.ApiErrorWriter;
import com.flowops.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a valid {@code Authorization: Bearer} access token into an authenticated
 * {@link FlowOpsPrincipal} in the {@code SecurityContext} (contract §1.1, §2).
 *
 * <p><strong>This filter performs no database access.</strong> Every input an
 * authorization decision needs — user, session, organization, role — is a verified
 * claim. That is what makes the token stateless, and it is the reason a
 * logged-out or role-changed token stays usable until {@code exp} (at most the
 * 15-minute TTL). Do not "fix" that with a per-request lookup; refresh tokens and
 * sessions are revoked immediately, which is what bounds the blast radius.
 *
 * <p>A malformed or expired token is rejected here with the contract's error
 * envelope rather than being passed along as anonymous — otherwise the caller
 * would get a generic 401 and no way to tell "expired, refresh and retry" from
 * "invalid, go to login".
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final ApiErrorWriter errorWriter;
    private final com.flowops.repository.UserRepository users;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            ApiErrorWriter errorWriter,
            com.flowops.repository.UserRepository users) {
        this.jwtService = jwtService;
        this.errorWriter = errorWriter;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        // No credential at all: stay anonymous. Whether that is acceptable is the
        // authorization layer's call, not this filter's — POST /api/auth/login has
        // no token either.
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        String token = header.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            reject(request, response, ErrorCode.TOKEN_INVALID);
            return;
        }

        FlowOpsPrincipal principal;
        try {
            principal = jwtService.verifyAccessToken(token);
        } catch (JwtValidationException ex) {
            reject(request, response, ex.isExpired() ? ErrorCode.TOKEN_EXPIRED : ErrorCode.TOKEN_INVALID);
            return;
        }

        Boolean verified = users.findEmailVerifiedById(principal.userId()).orElse(false);
        if (!Boolean.TRUE.equals(verified)) {
            reject(request, response, ErrorCode.EMAIL_NOT_VERIFIED);
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthentication(principal));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            // Thread-pool threads are reused; leaving a principal behind would leak
            // one caller's tenant context into the next request on this thread.
            SecurityContextHolder.clearContext();
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, ErrorCode code)
            throws IOException {
        SecurityContextHolder.clearContext();
        errorWriter.write(request, response, code, code.defaultMessage());
    }

    /**
     * The {@code Authentication} wrapper. Authorities are exposed as
     * {@code ROLE_OWNER}, … so Spring Security expressions can be used, but the
     * authoritative role check is {@link AuthenticatedUser#requireRole} comparing
     * rank — {@code hasRole('ADMIN')} would not accept an {@code OWNER}.
     */
    static final class JwtAuthentication extends AbstractAuthenticationToken {

        private final FlowOpsPrincipal principal;

        JwtAuthentication(FlowOpsPrincipal principal) {
            super(authorities(principal));
            this.principal = principal;
            setAuthenticated(true);
        }

        private static List<GrantedAuthority> authorities(FlowOpsPrincipal principal) {
            return List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));
        }

        @Override
        public FlowOpsPrincipal getPrincipal() {
            return principal;
        }

        /** No credential is retained — the token is not kept after verification. */
        @Override
        public Object getCredentials() {
            return null;
        }

        @Override
        public String getName() {
            return principal.userId().toString();
        }
    }
}
