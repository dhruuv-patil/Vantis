package com.flowops.security.oauth2;

import com.flowops.auth.AuthResult;
import com.flowops.auth.AuthService;
import com.flowops.auth.OAuthAccountService;
import com.flowops.auth.RefreshCookies;
import com.flowops.common.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Handles successful OAuth2 authentication (Google, GitHub).
 *
 * <p>Integrates with Vantis's existing authentication and session mechanism:
 * <ul>
 *   <li>Resolves or safely links the verified OAuth identity to a {@code UserAccount}.</li>
 *   <li>Establishes an {@code AuthSession} and issues a secure {@code flowops_refresh} HttpOnly cookie.</li>
 *   <li>Clears authorization request cookies and redirects the browser back to the frontend.</li>
 * </ul>
 */
@Component
public class OAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2AuthenticationSuccessHandler.class);

    private final OAuthAccountService oauthAccountService;
    private final AuthService authService;
    private final RefreshCookies refreshCookies;
    private final HttpCookieOAuth2AuthorizationRequestRepository authorizationRequestRepository;
    private final String frontendBaseUrl;

    public OAuth2AuthenticationSuccessHandler(
            OAuthAccountService oauthAccountService,
            @org.springframework.context.annotation.Lazy AuthService authService,
            RefreshCookies refreshCookies,
            HttpCookieOAuth2AuthorizationRequestRepository authorizationRequestRepository,
            @Value("${flowops.mail.app-base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.oauthAccountService = oauthAccountService;
        this.authService = authService;
        this.refreshCookies = refreshCookies;
        this.authorizationRequestRepository = authorizationRequestRepository;
        this.frontendBaseUrl = frontendBaseUrl.replaceAll("/+$", "");
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException {

        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            log.error("Expected OAuth2AuthenticationToken, got {}", authentication.getClass());
            response.sendRedirect(frontendBaseUrl + "/login?error=invalid_token_type");
            return;
        }

        String provider = token.getAuthorizedClientRegistrationId();
        OAuth2User principal = token.getPrincipal();

        String providerUserId;
        String email;
        boolean emailVerified;
        String fullName;
        String avatarUrl;

        if ("google".equalsIgnoreCase(provider)) {
            providerUserId = principal.getAttribute("sub");
            email = principal.getAttribute("email");
            emailVerified = Boolean.TRUE.equals(principal.getAttribute("email_verified"));
            fullName = principal.getAttribute("name");
            avatarUrl = principal.getAttribute("picture");
        } else if ("github".equalsIgnoreCase(provider)) {
            Object idObj = principal.getAttribute("id");
            providerUserId = idObj != null ? String.valueOf(idObj) : null;
            email = principal.getAttribute("email");
            emailVerified = Boolean.TRUE.equals(principal.getAttribute("email_verified"));
            Object nameObj = principal.getAttribute("name");
            fullName = nameObj != null ? String.valueOf(nameObj) : principal.getAttribute("login");
            avatarUrl = principal.getAttribute("avatar_url");
        } else {
            log.error("Unsupported OAuth provider: {}", provider);
            response.sendRedirect(frontendBaseUrl + "/login?error=unsupported_provider");
            return;
        }

        if (providerUserId == null || providerUserId.isBlank()) {
            log.error("Missing provider user ID from {}", provider);
            response.sendRedirect(frontendBaseUrl + "/login?error=missing_user_id");
            return;
        }

        OAuthAccountService.ResolvedUserContext context = oauthAccountService.processOAuthLogin(
                provider, providerUserId, email, emailVerified, fullName, avatarUrl);

        String userAgent = request.getHeader(HttpHeaders.USER_AGENT);
        String ip = RateLimiter.clientIp(request);

        AuthResult authResult = authService.startOAuthSession(
                context.user(), context.organization(), context.role(), userAgent, ip);

        if (authResult.hasRefreshToken()) {
            response.addHeader(
                    RefreshCookies.header(), refreshCookies.issue(authResult.refreshToken()));
        }

        String targetPath = authorizationRequestRepository.getAndClearRedirectTarget(request, response);
        authorizationRequestRepository.removeAuthorizationRequestCookies(request, response);

        if (targetPath == null || targetPath.isBlank()) {
            targetPath = "/dashboard";
        }
        if (!targetPath.startsWith("/")) {
            targetPath = "/" + targetPath;
        }

        response.sendRedirect(frontendBaseUrl + targetPath);
    }
}
