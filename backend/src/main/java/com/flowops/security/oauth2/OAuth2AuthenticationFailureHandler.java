package com.flowops.security.oauth2;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * Handles failed OAuth2 authentication attempts.
 *
 * <p>Cleans up authorization cookies and redirects the browser back to the login
 * page with a generic, user-friendly error indicator.
 */
@Component
public class OAuth2AuthenticationFailureHandler implements AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuth2AuthenticationFailureHandler.class);

    private final HttpCookieOAuth2AuthorizationRequestRepository authorizationRequestRepository;
    private final String frontendBaseUrl;

    public OAuth2AuthenticationFailureHandler(
            HttpCookieOAuth2AuthorizationRequestRepository authorizationRequestRepository,
            @Value("${flowops.mail.app-base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.authorizationRequestRepository = authorizationRequestRepository;
        this.frontendBaseUrl = frontendBaseUrl.replaceAll("/+$", "");
    }

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) throws IOException {

        log.warn("OAuth2 authentication failure: {}", exception.getMessage());
        authorizationRequestRepository.removeAuthorizationRequestCookies(request, response);

        String error = "oauth_failed";
        if (exception.getMessage() != null && exception.getMessage().toLowerCase().contains("verified email")) {
            error = "email_not_verified";
        }

        response.sendRedirect(frontendBaseUrl + "/login?error=" + URLEncoder.encode(error, StandardCharsets.UTF_8));
    }
}
