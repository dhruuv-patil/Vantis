package com.flowops.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.api.AuthResponse;
import com.flowops.common.error.ApiException;
import com.flowops.domain.OAuthAccount;
import com.flowops.domain.Role;
import com.flowops.domain.UserAccount;
import com.flowops.repository.OAuthAccountRepository;
import com.flowops.repository.UserRepository;
import com.flowops.security.oauth2.HttpCookieOAuth2AuthorizationRequestRepository;
import com.flowops.security.oauth2.OAuth2AuthenticationSuccessHandler;
import jakarta.servlet.http.Cookie;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OAuthSecurityIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private OAuthAccountRepository oauthAccounts;

    @Autowired
    private OAuthAccountService oauthAccountService;

    @Autowired
    private OAuth2AuthenticationSuccessHandler successHandler;

    @Autowired
    private HttpCookieOAuth2AuthorizationRequestRepository authorizationRequestRepository;

    // =========================================================================
    // 1. Password Authentication Edge Cases & Rejections
    // =========================================================================

    @Test
    @DisplayName("Password authentication: valid email + valid password succeeds")
    void loginWithValidCredentialsSucceeds() throws Exception {
        String email = "valid+" + UUID.randomUUID() + "@example.com";
        String password = "validPassword123";
        register(email, password, "Valid User", "Valid Org");

        // Legitimate user completes email verification before logging in
        UserAccount user = users.findByEmailIgnoreCase(email).orElseThrow();
        user.markEmailVerified();
        users.save(user);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email, "password", password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email.toLowerCase()));
    }

    @Test
    @DisplayName("Password authentication: unverified user cannot log in and receives 403 EMAIL_NOT_VERIFIED")
    void loginWithUnverifiedEmailFails() throws Exception {
        String email = "unverified+" + UUID.randomUUID() + "@example.com";
        String password = "validPassword123";
        register(email, password, "Unverified User", "Org");

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email, "password", password)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    @DisplayName("Password authentication: valid email + wrong password fails with 401 INVALID_CREDENTIALS")
    void loginWithWrongPasswordFails() throws Exception {
        String email = "wrongpass+" + UUID.randomUUID() + "@example.com";
        String password = "validPassword123";
        register(email, password, "Wrong Pass User", "Org");

        UserAccount user = users.findByEmailIgnoreCase(email).orElseThrow();
        user.markEmailVerified();
        users.save(user);

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email, "password", "completelyWrongPassword123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("Password authentication: nonexistent email fails with generic 401 INVALID_CREDENTIALS")
    void loginWithNonexistentEmailFails() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", "nonexistent+" + UUID.randomUUID() + "@example.com", "password", "anyPassword123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("Password authentication: arbitrary unauthenticated email fails with 401 INVALID_CREDENTIALS")
    void loginWithArbitraryEmailFails() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", "arbitrary.attacker@company.internal", "password", "guessPassword123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("Password authentication: empty password fails validation with 400 VALIDATION_ERROR")
    void loginWithEmptyPasswordFails() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", "someone@example.com", "password", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("Password authentication: malformed credentials (missing fields) fails with 400")
    void loginWithMalformedCredentialsFails() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"missing-password@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("Password authentication: attempt to log in with password on OAuth-only user fails")
    void loginWithPasswordOnOAuthOnlyUserFails() throws Exception {
        String email = "oauthonly+" + UUID.randomUUID() + "@example.com";
        // Create an OAuth-only user who has no passwordHash
        OAuthAccountService.ResolvedUserContext ctx = oauthAccountService.processOAuthLogin(
                "google", "sub-oauth-only-" + UUID.randomUUID(), email, true, "OAuth User", null);
        assertThat(ctx.user().getPasswordHash()).isNull();

        // Attempting to log in via password must fail with generic INVALID_CREDENTIALS
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("email", email, "password", "AttemptedPassword123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"));
    }

    // =========================================================================
    // 2. Google OAuth Integration Flows
    // =========================================================================

    @Test
    @DisplayName("Google OAuth: brand new user registers with workspace and OWNER role")
    void googleOAuthNewUserRegistration() {
        String email = "google.new+" + UUID.randomUUID() + "@example.com";
        String googleSub = "sub-" + UUID.randomUUID();

        OAuthAccountService.ResolvedUserContext context = oauthAccountService.processOAuthLogin(
                "google", googleSub, email, true, "Google Newbie", "https://photo.example/avatar.png");

        assertThat(context.isNewUser()).isTrue();
        assertThat(context.user().getEmail()).isEqualTo(email.toLowerCase());
        assertThat(context.user().getFullName()).isEqualTo("Google Newbie");
        assertThat(context.user().getAvatarUrl()).isEqualTo("https://photo.example/avatar.png");
        assertThat(context.role()).isEqualTo(Role.OWNER);
        assertThat(context.organization().getName()).contains("Google Newbie's Workspace");

        assertThat(oauthAccounts.findByProviderAndProviderUserId("google", googleSub)).isPresent();
    }

    @Test
    @DisplayName("Google OAuth: existing user with linked Google account logs in directly")
    void googleOAuthExistingUserLogin() {
        String email = "google.existing+" + UUID.randomUUID() + "@example.com";
        String googleSub = "sub-" + UUID.randomUUID();

        // First login (registers)
        OAuthAccountService.ResolvedUserContext first = oauthAccountService.processOAuthLogin(
                "google", googleSub, email, true, "Google Existing", null);
        assertThat(first.isNewUser()).isTrue();

        // Second login (resolves existing)
        OAuthAccountService.ResolvedUserContext second = oauthAccountService.processOAuthLogin(
                "google", googleSub, email, true, "Google Existing", null);
        assertThat(second.isNewUser()).isFalse();
        assertThat(second.user().getId()).isEqualTo(first.user().getId());
        assertThat(second.organization().getId()).isEqualTo(first.organization().getId());
    }

    @Test
    @DisplayName("Google OAuth: safe account linking with existing password-created user")
    void googleOAuthSafeAccountLinking() throws Exception {
        String email = "linking.google+" + UUID.randomUUID() + "@example.com";
        String password = "password1234";
        // User registers with email/password first
        register(email, password, "Original User", "Original Org");
        UserAccount existing = users.findByEmailIgnoreCase(email).orElseThrow();

        // Later signs in with Google with same verified email
        String googleSub = "sub-linking-" + UUID.randomUUID();
        OAuthAccountService.ResolvedUserContext context = oauthAccountService.processOAuthLogin(
                "google", googleSub, email, true, "Google Identity", null);

        assertThat(context.isNewUser()).isFalse();
        assertThat(context.user().getId()).isEqualTo(existing.getId());
        // Duplicate account was NOT created
        assertThat(users.findAll().stream().filter(u -> u.getEmail().equalsIgnoreCase(email)).count()).isEqualTo(1);
        // OAuthAccount is linked
        assertThat(oauthAccounts.findByProviderAndProviderUserId("google", googleSub)).isPresent();
    }

    @Test
    @DisplayName("Google OAuth: unverified email is rejected")
    void googleOAuthRejectsUnverifiedEmail() {
        String email = "unverified+" + UUID.randomUUID() + "@example.com";
        String googleSub = "sub-" + UUID.randomUUID();

        assertThatThrownBy(() -> oauthAccountService.processOAuthLogin(
                "google", googleSub, email, false, "Unverified User", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must have a verified email address");
    }

    // =========================================================================
    // 3. GitHub OAuth Integration Flows
    // =========================================================================

    @Test
    @DisplayName("GitHub OAuth: new user registers with workspace and OWNER role")
    void githubOAuthNewUserRegistration() {
        String email = "github.new+" + UUID.randomUUID() + "@example.com";
        String githubId = String.valueOf(System.currentTimeMillis());

        OAuthAccountService.ResolvedUserContext context = oauthAccountService.processOAuthLogin(
                "github", githubId, email, true, "GitHub Dev", "https://avatars.github.com/u/123");

        assertThat(context.isNewUser()).isTrue();
        assertThat(context.user().getEmail()).isEqualTo(email.toLowerCase());
        assertThat(context.user().getFullName()).isEqualTo("GitHub Dev");
        assertThat(context.role()).isEqualTo(Role.OWNER);

        assertThat(oauthAccounts.findByProviderAndProviderUserId("github", githubId)).isPresent();
    }

    @Test
    @DisplayName("GitHub OAuth: safe account linking with existing account")
    void githubOAuthSafeAccountLinking() throws Exception {
        String email = "linking.github+" + UUID.randomUUID() + "@example.com";
        register(email, "pass12345", "GitHub Link User", "Dev Org");
        UserAccount existing = users.findByEmailIgnoreCase(email).orElseThrow();

        String githubId = String.valueOf(System.currentTimeMillis() + 1);
        OAuthAccountService.ResolvedUserContext context = oauthAccountService.processOAuthLogin(
                "github", githubId, email, true, "GitHub Dev", null);

        assertThat(context.isNewUser()).isFalse();
        assertThat(context.user().getId()).isEqualTo(existing.getId());
        assertThat(oauthAccounts.findByProviderAndProviderUserId("github", githubId)).isPresent();
    }

    @Test
    @DisplayName("GitHub OAuth: unverified email is rejected")
    void githubOAuthRejectsUnverifiedEmail() {
        String email = "unverified.github+" + UUID.randomUUID() + "@example.com";
        String githubId = String.valueOf(System.currentTimeMillis() + 2);

        assertThatThrownBy(() -> oauthAccountService.processOAuthLogin(
                "github", githubId, email, false, "GitHub Dev", null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must have a verified email address");
    }

    // =========================================================================
    // 4. OAuth Success Handler End-to-End & Cookie Generation
    // =========================================================================

    @Test
    @DisplayName("OAuth success handler sets refresh cookie and redirects to frontend")
    void oauthSuccessHandlerIssuesSessionAndSetsCookie() throws Exception {
        String email = "success.handler+" + UUID.randomUUID() + "@example.com";
        String googleSub = "sub-" + UUID.randomUUID();

        DefaultOAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of(
                        "sub", googleSub,
                        "email", email,
                        "email_verified", true,
                        "name", "Full Name",
                        "picture", "https://photo.example/pic.jpg"),
                "sub");

        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                principal,
                principal.getAuthorities(),
                "google");

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("User-Agent", "Mozilla/5.0 Test");
        request.setRemoteAddr("127.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();

        successHandler.onAuthenticationSuccess(request, response, authentication);

        // Response should redirect to frontend dashboard
        assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000/dashboard");

        // Response must carry the Set-Cookie header for flowops_refresh
        String setCookie = response.getHeader("Set-Cookie");
        assertThat(setCookie).isNotNull();
        assertThat(setCookie).contains("flowops_refresh=");
        assertThat(setCookie).contains("Path=/api/auth");
        assertThat(setCookie).contains("HttpOnly");

        // The user must exist in the database
        UserAccount created = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(created.getFullName()).isEqualTo("Full Name");
        assertThat(created.getAvatarUrl()).isEqualTo("https://photo.example/pic.jpg");
    }

    // =========================================================================
    // 5. OAuth User Authorization & Protected Endpoints Access
    // =========================================================================

    @Test
    @DisplayName("Authenticated OAuth user can access protected endpoints like /api/auth/me")
    void oauthUserCanAccessProtectedApi() throws Exception {
        String email = "protected.api+" + UUID.randomUUID() + "@example.com";
        String googleSub = "sub-" + UUID.randomUUID();

        DefaultOAuth2User principal = new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of(
                        "sub", googleSub,
                        "email", email,
                        "email_verified", true,
                        "name", "Protected User"),
                "sub");

        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
                principal,
                principal.getAuthorities(),
                "google");

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        successHandler.onAuthenticationSuccess(request, response, authentication);

        String setCookieHeader = response.getHeader("Set-Cookie");
        String cookieValue = extractCookieValue(setCookieHeader, "flowops_refresh");

        // Call /api/auth/refresh with the issued cookie to get the JWT access token
        MvcResult refreshResult = mvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie("flowops_refresh", cookieValue)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andReturn();

        JsonNode refreshBody = MAPPER.readTree(refreshResult.getResponse().getContentAsString());
        String accessToken = refreshBody.get("accessToken").asText();

        // Use the access token to access protected /api/auth/me endpoint
        mvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.currentRole").value("OWNER"))
                .andExpect(jsonPath("$.currentOrganization.name").value(org.hamcrest.Matchers.containsString("Protected User's Workspace")));
    }

    // =========================================================================
    // 6. HttpCookieOAuth2AuthorizationRequestRepository Security
    // =========================================================================

    @Test
    @DisplayName("Authorization request repository sanitizes redirect targets against open redirect")
    void authorizationRequestRepositorySanitizesRedirects() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("next", "https://evil.com/phish");
        MockHttpServletResponse response = new MockHttpServletResponse();

        OAuth2AuthorizationRequest authReq = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                .clientId("test-client")
                .redirectUri("http://localhost:8080/login/oauth2/code/google")
                .state("state123")
                .build();

        authorizationRequestRepository.saveAuthorizationRequest(authReq, request, response);

        // Open redirect URL must NOT be saved in cookie
        assertThat(response.getCookies()).noneMatch(c ->
                c.getName().equals(HttpCookieOAuth2AuthorizationRequestRepository.REDIRECT_URI_PARAM_COOKIE_NAME));

        // Relative path must be saved
        MockHttpServletRequest safeRequest = new MockHttpServletRequest();
        safeRequest.setParameter("next", "/workflows/my-workflow");
        MockHttpServletResponse safeResponse = new MockHttpServletResponse();

        authorizationRequestRepository.saveAuthorizationRequest(authReq, safeRequest, safeResponse);
        assertThat(safeResponse.getCookies()).anyMatch(c ->
                c.getName().equals(HttpCookieOAuth2AuthorizationRequestRepository.REDIRECT_URI_PARAM_COOKIE_NAME));
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    private void register(String email, String password, String fullName, String orgName) throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(
                                "email", email,
                                "password", password,
                                "fullName", fullName,
                                "organizationName", orgName)))
                .andExpect(status().isCreated());
    }

    private static String json(Object... kvs) {
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < kvs.length; i += 2) {
            if (i > 0) b.append(",");
            b.append("\"").append(kvs[i]).append("\":\"").append(kvs[i + 1]).append("\"");
        }
        return b.append("}").toString();
    }

    private static String extractCookieValue(String header, String cookieName) {
        for (String part : header.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(cookieName + "=")) {
                return trimmed.substring((cookieName + "=").length());
            }
        }
        return null;
    }
}
