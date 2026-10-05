package com.flowops.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.domain.PasswordResetToken;
import com.flowops.integration.delivery.smtp.SmtpSettings;
import com.flowops.integration.delivery.smtp.SmtpTransport;
import com.flowops.repository.PasswordResetTokenRepository;
import com.flowops.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Security-focused integration tests for the auth system (contract §5, §1).
 *
 * <p>Covers, end to end against the H2 test datasource: registration (including
 * duplicate-email and the 72-byte BCrypt cap), login (identical error for unknown
 * address vs wrong password — no enumeration), the {@code me} identity read,
 * refresh-token rotation and <em>reuse detection</em> (a replayed token destroys
 * every session), logout idempotency, change-password "sign out other devices",
 * and the full forgot/reset password round trip where the reset token is forged
 * directly into the repository via {@link OpaqueTokens} (package-private, so this
 * test lives in {@code com.flowops.auth}).
 *
 * <p>Rate limiting is disabled in the test profile
 * ({@code flowops.ratelimit.enabled: false}); hitting the shared in-memory limiter
 * with dozens of register/login calls would flake the suite, and the limiter has
 * its own focused unit test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthSecurityIntegrationTest {

    /** Matches {@code flowops.auth.jwt-secret} in application-test.yml. */
    private static final String TEST_JWT_SECRET =
            "flowops-test-only-jwt-secret-not-for-production-0123456789";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordResetTokenRepository resetTokens;

    @Autowired
    private RecordingSmtpTransport recordingSmtp;

    /**
     * Replaces the real socket SMTP transport so no auth test opens a network
     * connection: password-reset delivery is captured in memory and inspected
     * instead. (The test profile configures {@code flowops.mail}, so the mailer
     * goes through its normal configured path.)
     */
    @TestConfiguration
    static class RecordingSmtpConfig {
        @Bean
        @Primary
        RecordingSmtpTransport smtpTransport() {
            return new RecordingSmtpTransport();
        }
    }

    /** A hand-written {@link SmtpTransport} fake (no Mockito) that records sends. */
    static final class RecordingSmtpTransport implements SmtpTransport {
        final List<SentMail> sent = new ArrayList<>();

        void reset() {
            sent.clear();
        }

        @Override
        public void testConnection(SmtpSettings settings) {
        }

        @Override
        public void send(SmtpSettings settings, String to, String subject, String body) {
            sent.add(new SentMail(to, subject, body));
        }

        record SentMail(String to, String subject, String body) {
        }
    }

    // =========================================================================
    // Registration
    // =========================================================================

    @Test
    void registerOpensSessionAndIssuesHttpOnlyRefreshCookie() throws Exception {
        String email = "reg+" + UUID.randomUUID() + "@example.com";

        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "email", email,
                                "password", "pass-word-1234",
                                "fullName", "Regina User",
                                "organizationName", "Regina's Org")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.currentRole").value("OWNER"))
                .andExpect(jsonPath("$.memberships.length()").value(1))
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.currentOrganization.name").value("Regina's Org"))
                .andReturn();

        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("expiresIn").asLong()).isPositive();

        // The refresh token must never appear in a response body.
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("refreshToken");

        // The Set-Cookie is HttpOnly, path-scoped to the auth endpoints, Lax, and
        // host-only (no Domain attribute).
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).startsWith("flowops_refresh=");
        assertThat(setCookie.toLowerCase()).contains("httponly");
        assertThat(setCookie.toLowerCase()).contains("path=/api/auth");
        assertThat(setCookie.toLowerCase()).contains("samesite=lax");
        assertThat(setCookie.toLowerCase()).doesNotContain("domain=");
    }

    @Test
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        String email = "dupe+" + UUID.randomUUID() + "@example.com";
        register(email, "pass-word-1234", "First User", "Org One");

        // Same email, different case: the lower(email) unique index must catch it.
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "email", email.toUpperCase(),
                                "password", "pass-word-1234",
                                "fullName", "Second User",
                                "organizationName", "Org Two")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("EMAIL_ALREADY_REGISTERED"));

        // Neither register nor login may leak whether an org is reused, so this
        // must be EMAIL_ALREADY_REGISTERED, not an org collision.
        assertThat(users.findByEmailIgnoreCase(email)).isPresent();
    }

    @Test
    void registerRejectsMalformedInputs() throws Exception {
        String email = "malformed+" + UUID.randomUUID() + "@example.com";

        // Bad email.
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "email", "not-an-email",
                                "password", "pass-word-1234",
                                "fullName", "Mal User",
                                "organizationName", "Org")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("email"));

        // Weak password (no digit).
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "email", email,
                                "password", "passwordonly",
                                "fullName", "Mal User",
                                "organizationName", "Org")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("password"));
    }

    @Test
    void registerRejectsMultibytePasswordOverBcryptByteCap() throws Exception {
        // 63 chars (passes the 8-72 character rule and has letters + digits),
        // but 81 UTF-8 bytes: BCrypt would silently truncate past 72.
        String longMultibyte = "abc123".repeat(9) + "字字字字字字字字字";

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "email", "bytes+" + UUID.randomUUID() + "@example.com",
                                "password", longMultibyte,
                                "fullName", "Byte User",
                                "organizationName", "Org")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("password"));
    }

    // =========================================================================
    // Login
    // =========================================================================

    @Test
    void loginMintsANewSessionWithoutEnumeration() throws Exception {
        String email = "login+" + UUID.randomUUID() + "@example.com";
        String password = "pass-word-1234";
        register(email, password, "Dave Login", "Login Org");

        // Wrong password.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email, "password", "definitely-wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

        // Unknown email — must be byte-for-byte the same code.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "email", "nobody+" + UUID.randomUUID() + "@example.com",
                                "password", password)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));

        // Login is case-insensitive on email; every login mints a fresh cookie.
        MvcResult first = login(email.toUpperCase(), password);
        MvcResult second = login(email, password);
        assertThat(first.getResponse().getHeader("Set-Cookie"))
                .isNotEqualTo(second.getResponse().getHeader("Set-Cookie"));
    }

    // =========================================================================
    // me
    // =========================================================================

    @Test
    void meReturnsIdentityAndTenantFromToken() throws Exception {
        String email = "me+" + UUID.randomUUID() + "@example.com";
        Session session = register(email, "pass-word-1234", "Me User", "Me Org");

        mvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.currentRole").value("OWNER"));
    }

    @Test
    void meRejectsMissingInvalidAndExpiredTokens() throws Exception {
        // No token at all.
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

        // Garbage token.
        mvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_INVALID"));

        // A well-formed but past-expiry token signed with the test secret.
        String expired = expiredAccessToken();
        mvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("TOKEN_EXPIRED"));
    }

    // =========================================================================
    // Refresh rotation + reuse detection
    // =========================================================================

    @Test
    void refreshRotatesTheToken() throws Exception {
        Session session = register(
                "rotate+" + UUID.randomUUID() + "@example.com",
                "pass-word-1234", "Rotate User", "Rotate Org");

        MvcResult rotated = refreshWith(session.refreshToken());
        assertThat(rotated.getResponse().getStatus()).isEqualTo(HttpServletResponse.SC_OK);

        String newCookie = rotated.getResponse().getHeader("Set-Cookie");
        assertThat(newCookie).startsWith("flowops_refresh=");
        // Rotation always mints a fresh token — never the same cookie.
        assertThat(cookieValue(rotated)).isNotEqualTo(session.refreshToken());

        String newAccessToken = MAPPER.readTree(
                        rotated.getResponse().getContentAsString())
                .get("accessToken").asText();
        assertThat(newAccessToken).isNotBlank();

        // The rotated token authenticates a /me read.
        mvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + newAccessToken))
                .andExpect(status().isOk());
    }

    @Test
    void reusingARotatedTokenDestroysEverySession() throws Exception {
        Session session = register(
                "reuse+" + UUID.randomUUID() + "@example.com",
                "pass-word-1234", "Reuse User", "Reuse Org");

        // First refresh succeeds and rotates: old cookie R0 -> new cookie R1.
        MvcResult rotated = refreshWith(session.refreshToken());
        assertThat(rotated.getResponse().getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        String r1 = cookieValue(rotated);

        // Replaying the spent R0 is detected as theft.
        mvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("flowops_refresh", session.refreshToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_INVALID"));

        // Reuse destroys EVERYTHING for the user: even the newer, never-used R1 is
        // now dead because its session was revoked.
        assertThat(refreshWith(r1).getResponse().getStatus())
                .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        // The user can still sign in again — this is not a lockout.
        login(session.email(), "pass-word-1234");
    }

    @Test
    void refreshWithoutAUsableCookieClearsIt() throws Exception {
        // No cookie at all.
        MvcResult none = mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_INVALID"))
                .andReturn();
        assertThat(none.getResponse().getHeader("Set-Cookie"))
                .contains("Max-Age=0");

        // Garbage cookie.
        mvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("flowops_refresh", "garbage-token"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REFRESH_TOKEN_INVALID"));
    }

    // =========================================================================
    // Logout
    // =========================================================================

    @Test
    void logoutRevokesTheSessionAndIsAlways204() throws Exception {
        Session session = register(
                "logout+" + UUID.randomUUID() + "@example.com",
                "pass-word-1234", "Logout User", "Logout Org");

        // With a valid bearer token: 204 + cookie cleared.
        mvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNoContent())
                .andExpect(result ->
                        assertThat(result.getResponse().getHeader("Set-Cookie"))
                                .contains("Max-Age=0"));

        // The revoked session's refresh cookie is dead.
        assertThat(refreshWith(session.refreshToken()).getResponse().getStatus())
                .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        // Logging out again — with nothing at all usable — is still 204.
        mvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void logoutFallsBackToTheRefreshCookie() throws Exception {
        Session session = register(
                "logout-cookie+" + UUID.randomUUID() + "@example.com",
                "pass-word-1234", "Cookie Logout", "Cookie Org");

        // No bearer token, only the refresh cookie: the session is still found.
        mvc.perform(post("/api/auth/logout")
                        .cookie(new Cookie("flowops_refresh", session.refreshToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNoContent());

        assertThat(refreshWith(session.refreshToken()).getResponse().getStatus())
                .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    // =========================================================================
    // Change password ("sign out other devices")
    // =========================================================================

    @Test
    void changePasswordRevokesOtherSessionsButKeepsTheCaller() throws Exception {
        String email = "chpw+" + UUID.randomUUID() + "@example.com";
        Session first = register(email, "pass-word-1234", "Chpw User", "Chpw Org");

        // A second device: a fresh login creates a second session.
        Session second = sessionFrom(login(email, "pass-word-1234"), email);

        // Wrong current password is rejected before anything is touched.
        mvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + first.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "currentPassword", "not-the-password",
                                "newPassword", "new-pass-word-99")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_PASSWORD"));

        // Correct change: 204.
        mvc.perform(post("/api/auth/change-password")
                        .header("Authorization", "Bearer " + first.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "currentPassword", "pass-word-1234",
                                "newPassword", "new-pass-word-99")))
                .andExpect(status().isNoContent());

        // The second device's refresh token was revoked.
        assertThat(refreshWith(second.refreshToken()).getResponse().getStatus())
                .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        // The calling session survives.
        assertThat(refreshWith(first.refreshToken()).getResponse().getStatus())
                .isEqualTo(HttpServletResponse.SC_OK);

        // The old password no longer authenticates; the new one does.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email, "password", "pass-word-1234")))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email, "password", "new-pass-word-99")))
                .andExpect(status().isOk());
    }

    @Test
    void changePasswordRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "currentPassword", "whatever",
                                "newPassword", "new-pass-word-99")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    // =========================================================================
    // Forgot / reset password
    // =========================================================================

    @Test
    void forgotPasswordIsReachableAnonymouslyAndNeverEnumerates() throws Exception {
        String known = "forgot-known+" + UUID.randomUUID() + "@example.com";
        register(known, "pass-word-1234", "Forgot User", "Forgot Org");

        // Both a known and an unknown email get the same identical 204.
        mvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", known)))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", "nobody+" + UUID.randomUUID() + "@example.com")))
                .andExpect(status().isNoContent());
    }

    @Test
    void forgotPasswordEmailsAResetLinkWhoseTokenResetsThePassword() throws Exception {
        String email = "mail-known+" + UUID.randomUUID() + "@example.com";
        register(email, "pass-word-1234", "Mail User", "Mail Org");
        recordingSmtp.reset();

        mvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email)))
                .andExpect(status().isNoContent());

        // Exactly one email went out, addressed to the requesting account.
        assertThat(recordingSmtp.sent).hasSize(1);
        RecordingSmtpTransport.SentMail mail = recordingSmtp.sent.get(0);
        assertThat(mail.to()).isEqualTo(email);
        assertThat(mail.subject()).contains("Vantis");

        // The body carries the reset link with the plaintext (43-char base64url)
        // token — the hashed form would not fit the contract of the reset page.
        assertThat(mail.body()).contains("/reset-password?token=");
        String emailedToken = mail.body()
                .substring(mail.body().indexOf("/reset-password?token=")
                        + "/reset-password?token=".length())
                .lines()
                .findFirst()
                .orElseThrow();
        assertThat(emailedToken).matches("[A-Za-z0-9_-]{43}");

        // The exact token sent by email completes the reset, and the pre-reset
        // session is revoked as a result.
        Session session = sessionFrom(login(email, "pass-word-1234"), email);
        mvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("token", emailedToken, "newPassword", "new-pass-word-99")))
                .andExpect(status().isNoContent());
        assertThat(refreshWith(session.refreshToken()).getResponse().getStatus())
                .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
    }

    @Test
    void forgotPasswordSendsNoEmailForAnUnknownAccount() throws Exception {
        recordingSmtp.reset();

        mvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", "nobody+" + UUID.randomUUID() + "@example.com")))
                .andExpect(status().isNoContent());

        assertThat(recordingSmtp.sent).isEmpty();
    }

    @Test
    void resetPasswordRoundTripChangesPasswordAndRevokesAllSessions() throws Exception {
        String email = "reset+" + UUID.randomUUID() + "@example.com";
        String password = "pass-word-1234";
        Session session = register(email, password, "Reset User", "Reset Org");

        // Ask for a reset (anonymously) — the plaintext goes only to email, so
        // forge a valid token straight into the repository for the user.
        mvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email)))
                .andExpect(status().isNoContent());

        UUID userId = users.findByEmailIgnoreCase(email).orElseThrow().getId();
        resetTokens.save(PasswordResetToken.create(
                userId,
                OpaqueTokens.hash("TEST-RESET-TOKEN"),
                Instant.now().plus(1, ChronoUnit.HOURS)));

        // Complete the reset anonymously with the forged token.
        mvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "token", "TEST-RESET-TOKEN",
                                "newPassword", "new-pass-word-99")))
                .andExpect(status().isNoContent());

        // A compromised password signs the user out everywhere: the session that
        // existed before the reset is dead.
        assertThat(refreshWith(session.refreshToken()).getResponse().getStatus())
                .isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);

        // Old password dead, new password live.
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email, "password", password)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email, "password", "new-pass-word-99")))
                .andExpect(status().isOk());

        // The token is single-use: replaying it fails identically to any bad token.
        mvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "token", "TEST-RESET-TOKEN",
                                "newPassword", "another-pass-77")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("RESET_TOKEN_INVALID"));
    }

    @Test
    void resetPasswordRejectsExpiredUnknownAndInvalidTokens() throws Exception {
        String email = "reset-bad+" + UUID.randomUUID() + "@example.com";
        register(email, "pass-word-1234", "Bad Reset", "Bad Org");

        UUID userId = users.findByEmailIgnoreCase(email).orElseThrow().getId();
        resetTokens.save(PasswordResetToken.create(
                userId,
                OpaqueTokens.hash("EXPIRED-TOKEN"),
                Instant.now().minus(1, ChronoUnit.MINUTES)));

        // Expired token.
        mvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "token", "EXPIRED-TOKEN",
                                "newPassword", "new-pass-word-99")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("RESET_TOKEN_INVALID"));

        // Unknown token.
        mvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "token", "NEVER-ISSUED-TOKEN",
                                "newPassword", "new-pass-word-99")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("RESET_TOKEN_INVALID"));

        // Missing token fails validation.
        mvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("newPassword", "new-pass-word-99")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.error.fieldErrors[0].field").value("token"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private record Session(
            String email, String accessToken, String refreshToken) {
    }

    private Session register(String email, String password, String name, String org)
            throws Exception {

        MvcResult result = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "email", email,
                                "password", password,
                                "fullName", name,
                                "organizationName", org)))
                .andExpect(status().isCreated())
                .andReturn();

        return sessionFrom(result, email);
    }

    private MvcResult login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email, "password", password)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private Session sessionFrom(MvcResult result, String email) throws Exception {
        JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
        return new Session(
                email,
                body.get("accessToken").asText(),
                cookieValue(result));
    }

    private MvcResult refreshWith(String token) throws Exception {
        return mvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("flowops_refresh", token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
    }

    /** The plaintext refresh token from a response's Set-Cookie header. */
    private static String cookieValue(MvcResult result) {
        String header = result.getResponse().getHeader("Set-Cookie");
        assertThat(header).startsWith("flowops_refresh=");
        return header.substring("flowops_refresh=".length(), header.indexOf(';'));
    }

    /** A well-formed access token signed with the test secret but long since expired. */
    private static String expiredAccessToken() {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        return Jwts.builder()
                .issuer("flowops-test")
                .audience().add("flowops-test").and()
                .subject(UUID.randomUUID().toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(past.minusSeconds(1800)))
                .expiration(Date.from(past))
                .claim("tokenUse", "access")
                .claim("sid", UUID.randomUUID().toString())
                .claim("orgId", UUID.randomUUID().toString())
                .claim("role", "VIEWER")
                .claim("email", "expired@example.com")
                .signWith(Keys.hmacShaKeyFor(
                        TEST_JWT_SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();
    }

    private static String json(String... pairs) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < pairs.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(pairs[i]).append("\":\"")
                    .append(pairs[i + 1]).append('"');
        }
        return sb.append('}').toString();
    }
}