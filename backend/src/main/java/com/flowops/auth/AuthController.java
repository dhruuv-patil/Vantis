package com.flowops.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowops.api.SessionResponse;
import com.flowops.auth.ForgotPasswordRequest;
import com.flowops.auth.ResetPasswordRequest;
import com.flowops.common.ratelimit.RateLimiter;
import com.flowops.security.AuthenticatedUser;
import com.flowops.security.FlowOpsPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The five auth endpoints (contract §5.1–§5.5).
 *
 * <p>Two invariants live here rather than in the service, because this is the only
 * layer that can see the HTTP response:
 *
 * <ul>
 *   <li>The refresh token leaves the server <em>only</em> as a {@code Set-Cookie}
 *       value. {@link AuthResult} carries it beside the body precisely so the two
 *       cannot be confused, and no response type in {@code com.flowops.api} has a
 *       component to put it in.
 *   <li>{@code consumes = application/json} on the three POSTs is the CSRF defense
 *       (contract §1.4): a cross-site form cannot set that content type, so the
 *       browser must preflight, and the preflight fails on origin. A wrong type
 *       lands as {@code 415 UNSUPPORTED_MEDIA_TYPE}, not a processed request.
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final int LOGIN_LIMIT = 10;

    private static final Duration REGISTER_WINDOW = Duration.ofMinutes(60);
    private static final int REGISTER_LIMIT = 5;

    private static final Duration REFRESH_WINDOW = Duration.ofMinutes(15);
    private static final int REFRESH_LIMIT = 60;

    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordResetService passwordResetService;
    private final RefreshCookies refreshCookies;
    private final RateLimiter rateLimiter;

    @org.springframework.beans.factory.annotation.Autowired
    public AuthController(
            AuthService authService,
            EmailVerificationService emailVerificationService,
            PasswordResetService passwordResetService,
            RefreshCookies refreshCookies,
            RateLimiter rateLimiter) {
        this.authService = authService;
        this.emailVerificationService = emailVerificationService;
        this.passwordResetService = passwordResetService;
        this.refreshCookies = refreshCookies;
        this.rateLimiter = rateLimiter;
    }

    public AuthController(
            AuthService authService,
            PasswordResetService passwordResetService,
            RefreshCookies refreshCookies,
            RateLimiter rateLimiter) {
        this(authService, null, passwordResetService, refreshCookies, rateLimiter);
    }

    /** {@code 201} with user info and verification notice; no sessions or cookies issued. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, path = "/register")
    @Operation(summary = "Create an unverified user account and organization, and send verification email")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {

        rateLimiter.check(
                "register:ip:" + RateLimiter.clientIp(http), REGISTER_LIMIT, REGISTER_WINDOW);

        RegisterResponse result = authService.register(request, userAgent(http), RateLimiter.clientIp(http));
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * {@code 200} and a <em>new</em> session; other devices keep theirs
     * (contract §5.2).
     *
     * <p>Throttled on IP <em>and</em> normalized email so one address cannot be
     * sprayed from one host, while a shared NAT does not lock everyone behind it
     * out of their own accounts.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, path = "/login")
    @Operation(summary = "Exchange credentials for an access token and a refresh cookie")
    public ResponseEntity<?> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest http) {

        rateLimiter.check(
                "login:" + RateLimiter.clientIp(http) + ':' + request.email(),
                LOGIN_LIMIT,
                LOGIN_WINDOW);

        AuthResult result = authService.login(request, userAgent(http), RateLimiter.clientIp(http));
        return respond(HttpStatus.OK, result);
    }

    /**
     * Rotates the cookie and returns the whole session, so app boot is one round
     * trip rather than {@code refresh} + {@code me} (contract §5.3).
     *
     * <p>The body is parsed but never read: declaring it makes invalid JSON fail as
     * {@code 400 MALFORMED_REQUEST}, and declaring it optional keeps a
     * zero-length body — which some clients send for a POST with no data — working.
     * Any {@code Authorization} header is ignored, so an expired access token is
     * fine here.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, path = "/refresh")
    @Operation(summary = "Rotate the refresh cookie and mint a new access token")
    public ResponseEntity<?> refresh(
            @RequestBody(required = false) JsonNode ignoredBody,
            HttpServletRequest http,
            HttpServletResponse response) {

        rateLimiter.check(
                "refresh:ip:" + RateLimiter.clientIp(http), REFRESH_LIMIT, REFRESH_WINDOW);

        String presented = refreshCookies.read(http).orElse(null);
        try {
            AuthResult result = authService.refresh(presented);
            return respond(HttpStatus.OK, result);
        } catch (RuntimeException failure) {
            // A refresh that cannot succeed leaves behind a cookie the browser would
            // keep replaying, so clear it here and let the error handler finish the
            // response — headers already set survive the @ExceptionHandler path.
            // Deliberately outside the throttle check above: a 429 must not log
            // anyone out.
            response.setHeader(RefreshCookies.header(), refreshCookies.clear());
            throw failure;
        }
    }

    /**
     * Always {@code 204}, always clears the cookie (contract §5.4).
     *
     * <p>Never {@code 401}: a user clicking "log out" in a tab whose token died
     * hours ago has to end up logged out. The bearer is therefore read through
     * {@link AuthenticatedUser#optional()} and the cookie is the fallback.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, path = "/logout")
    @Operation(summary = "Revoke the current session and clear the refresh cookie")
    public ResponseEntity<Void> logout(
            @RequestBody(required = false) JsonNode ignoredBody, HttpServletRequest http) {

        UUID sessionId = AuthenticatedUser.optional()
                .map(FlowOpsPrincipal::sessionId)
                .orElse(null);

        authService.logout(sessionId, refreshCookies.read(http).orElse(null));

        return ResponseEntity.noContent()
                .header(RefreshCookies.header(), refreshCookies.clear())
                .build();
    }

    /** Identity and tenant context, read fresh from the database (contract §5.5). */
    @GetMapping("/me")
    @Operation(summary = "The current session as the database sees it right now")
    public SessionResponse me() {
        return authService.currentSession(AuthenticatedUser.require());
    }

    /**
     * Changes the caller's password (Slice 3, security settings). {@code 204} on
     * success; other devices are signed out while this session survives. The current
     * password is re-verified, so this is safe to expose to any authenticated user.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, path = "/change-password")
    @Operation(summary = "Change the current user's password and revoke other sessions")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        FlowOpsPrincipal principal = AuthenticatedUser.require();
        authService.changePassword(principal, request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent().build();
    }

    /**
     * Initiates a password reset. Always returns {@code 204} with a generic message
     * (contract §5.x): never reveals whether the email is registered. If the email
     * exists, a short-lived, single-use reset token is generated and passed to the
     * email delivery abstraction.
     *
     * <p>Rate-limited per IP + normalized email.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, path = "/forgot-password")
    @Operation(summary = "Request a password reset link (always returns 204)")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {

        rateLimiter.check(
                "forgot-password:ip:" + RateLimiter.clientIp(http),
                5,
                Duration.ofMinutes(60));

        passwordResetService.forgotPassword(request, RateLimiter.clientIp(http));
        return ResponseEntity.noContent().build();
    }

    /**
     * Completes a password reset using a token from the email link. Validates the
     * token (exists, not used, not expired), hashes the new password, updates the
     * user, marks the token used, and revokes all sessions/refresh tokens for the
     * user so a compromised password results in immediate sign-out everywhere.
     *
     * <p>Returns {@code 204} on success. {@code 401 REFRESH_TOKEN_INVALID} for an
     * invalid/expired/used token — no distinction is made.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, path = "/reset-password")
    @Operation(summary = "Reset password with a token from the email link")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request);
        return ResponseEntity.noContent().build();
    }

    /**
     * Verifies user email with a single-use token from the email link.
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, path = "/verify-email")
    @Operation(summary = "Verify user email with a token from the email link")
    public ResponseEntity<VerifyEmailResponse> verifyEmail(
            @Valid @RequestBody VerifyEmailRequest request, HttpServletRequest http) {

        rateLimiter.check(
                "verify-email:ip:" + RateLimiter.clientIp(http), 10, Duration.ofMinutes(15));

        String email = emailVerificationService.verifyEmail(request.token());
        return ResponseEntity.ok(new VerifyEmailResponse("Email verified successfully.", email));
    }

    /**
     * Resends an email verification link. Always returns 200 with generic message (anti-enumeration).
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, path = "/resend-verification")
    @Operation(summary = "Resend email verification link (always returns generic 200)")
    public ResponseEntity<ResendVerificationResponse> resendVerification(
            @Valid @RequestBody ResendVerificationRequest request, HttpServletRequest http) {

        rateLimiter.check(
                "resend-verification:ip:" + RateLimiter.clientIp(http), 5, Duration.ofMinutes(60));
        rateLimiter.check(
                "resend-verification:email:" + request.email().toLowerCase(java.util.Locale.ROOT).trim(), 3, Duration.ofMinutes(60));

        emailVerificationService.resendVerification(request.email());
        return ResponseEntity.ok(new ResendVerificationResponse(
                "If an account exists for that email and is not yet verified, a verification link has been sent."));
    }

    /* ------------------------------------------------------------------ util */

    /**
     * Attaches the {@code Set-Cookie} header when — and only when — the service
     * produced a new refresh token, and serializes {@link AuthResult#response()},
     * which has no field the token could leak through.
     */
    private ResponseEntity<?> respond(HttpStatus status, AuthResult result) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (result.hasRefreshToken()) {
            builder = builder.header(
                    RefreshCookies.header(), refreshCookies.issue(result.refreshToken()));
        }
        return builder.body(result.response());
    }

    /** Descriptive only — stored on the session row for the future device list. */
    private static String userAgent(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.USER_AGENT);
    }
}
