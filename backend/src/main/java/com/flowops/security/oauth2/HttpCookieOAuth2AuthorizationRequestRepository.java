package com.flowops.security.oauth2;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectInputFilter;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/**
 * Cookie-based stateless repository for OAuth2 authorization requests.
 *
 * <p>Spring Security's default repository uses {@code HttpSession}, which breaks
 * under {@code SessionCreationPolicy.STATELESS} and multi-instance deployments.
 * This implementation stores the short-lived authorization request in an HttpOnly
 * cookie with a 3-minute TTL, enabling strict statelessness, CSRF state verification,
 * and seamless recovery during the OAuth redirect callback.
 */
@Component
public class HttpCookieOAuth2AuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    private static final Logger log =
            LoggerFactory.getLogger(HttpCookieOAuth2AuthorizationRequestRepository.class);

    public static final String OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME = "flowops_oauth2_auth_req";
    public static final String REDIRECT_URI_PARAM_COOKIE_NAME = "flowops_oauth2_target";
    private static final int COOKIE_EXPIRE_SECONDS = 180;

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return getCookie(request, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME)
                .map(this::deserialize)
                .orElse(null);
    }

    @Override
    public void saveAuthorizationRequest(
            OAuth2AuthorizationRequest authorizationRequest,
            HttpServletRequest request,
            HttpServletResponse response) {

        if (authorizationRequest == null) {
            deleteCookie(request, response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME);
            deleteCookie(request, response, REDIRECT_URI_PARAM_COOKIE_NAME);
            return;
        }

        String serialized = serialize(authorizationRequest);
        addCookie(response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME, serialized, COOKIE_EXPIRE_SECONDS);

        // Preserve safe relative in-app redirect target (e.g. "?next=/workflows")
        String nextParam = request.getParameter("next");
        if (nextParam == null || nextParam.isBlank()) {
            nextParam = request.getParameter("redirect_uri");
        }
        if (nextParam != null && !nextParam.isBlank()) {
            String sanitized = sanitizeRedirectUri(nextParam);
            if (sanitized != null) {
                addCookie(response, REDIRECT_URI_PARAM_COOKIE_NAME,
                        URLEncoder.encode(sanitized, StandardCharsets.UTF_8), COOKIE_EXPIRE_SECONDS);
            }
        }
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(
            HttpServletRequest request, HttpServletResponse response) {
        OAuth2AuthorizationRequest original = loadAuthorizationRequest(request);
        deleteCookie(request, response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME);
        return original;
    }

    public void removeAuthorizationRequestCookies(HttpServletRequest request, HttpServletResponse response) {
        deleteCookie(request, response, OAUTH2_AUTHORIZATION_REQUEST_COOKIE_NAME);
        deleteCookie(request, response, REDIRECT_URI_PARAM_COOKIE_NAME);
    }

    public String getAndClearRedirectTarget(HttpServletRequest request, HttpServletResponse response) {
        String target = getCookie(request, REDIRECT_URI_PARAM_COOKIE_NAME)
                .map(cookie -> URLDecoder.decode(cookie.getValue(), StandardCharsets.UTF_8))
                .orElse(null);
        deleteCookie(request, response, REDIRECT_URI_PARAM_COOKIE_NAME);
        return target;
    }

    /**
     * Prevents open-redirect vulnerabilities: redirect targets MUST be internal relative paths
     * starting with "/" and NOT starting with "//" or containing schema protocols.
     */
    private String sanitizeRedirectUri(String uri) {
        String trimmed = uri.trim();
        if (trimmed.startsWith("/") && !trimmed.startsWith("//") && !trimmed.contains("://")) {
            return trimmed;
        }
        return null;
    }

    private java.util.Optional<Cookie> getCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals(name)) {
                    return java.util.Optional.of(cookie);
                }
            }
        }
        return java.util.Optional.empty();
    }

    private void addCookie(HttpServletResponse response, String name, String value, int maxAge) {
        Cookie cookie = new Cookie(name, value);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(maxAge);
        // SameSite=Lax allows the cookie to be sent on top-level GET navigation from OAuth provider
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    private void deleteCookie(HttpServletRequest request, HttpServletResponse response, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals(name)) {
                    Cookie clearCookie = new Cookie(name, "");
                    clearCookie.setPath("/");
                    clearCookie.setHttpOnly(true);
                    clearCookie.setMaxAge(0);
                    clearCookie.setAttribute("SameSite", "Lax");
                    response.addCookie(clearCookie);
                }
            }
        }
    }

    private String serialize(OAuth2AuthorizationRequest object) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(object);
            return Base64.getUrlEncoder().encodeToString(baos.toByteArray());
        } catch (Exception ex) {
            log.error("Failed to serialize OAuth2AuthorizationRequest", ex);
            throw new IllegalStateException("Failed to serialize OAuth2 request", ex);
        }
    }

    private OAuth2AuthorizationRequest deserialize(Cookie cookie) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cookie.getValue());
            try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
                 ObjectInputStream ois = new ObjectInputStream(bais)) {
                // Security hardening: restrict deserialization filter to known OAuth2 types
                ois.setObjectInputFilter(ObjectInputFilter.Config.createFilter(
                        "org.springframework.security.oauth2.**;java.util.**;java.lang.**;!*"));
                return (OAuth2AuthorizationRequest) ois.readObject();
            }
        } catch (Exception ex) {
            log.warn("Failed to deserialize OAuth2AuthorizationRequest from cookie: {}", ex.getMessage());
            return null;
        }
    }
}
