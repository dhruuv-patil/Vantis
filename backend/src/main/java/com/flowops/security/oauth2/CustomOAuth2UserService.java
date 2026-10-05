package com.flowops.security.oauth2;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

/**
 * Custom OAuth2 user service for non-OIDC providers (e.g. GitHub).
 *
 * <p>Handles GitHub's email privacy behavior: when a user has set their email
 * to private on GitHub, the primary userinfo endpoint returns {@code null} or
 * a noreply alias. This service queries {@code https://api.github.com/user/emails}
 * using the authorized access token to locate a verified primary email, and rejects
 * accounts without a verified email address.
 */
@Service
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private static final Logger log = LoggerFactory.getLogger(CustomOAuth2UserService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Override
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oauth2User = super.loadUser(userRequest);
        String registrationId = userRequest.getClientRegistration().getRegistrationId();

        Map<String, Object> attributes = new HashMap<>(oauth2User.getAttributes());

        if ("github".equalsIgnoreCase(registrationId)) {
            String accessToken = userRequest.getAccessToken().getTokenValue();
            String verifiedEmail = resolveGitHubVerifiedEmail(attributes, accessToken);
            attributes.put("email", verifiedEmail);
            attributes.put("email_verified", true);
        }

        String userNameAttributeName = userRequest.getClientRegistration()
                .getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName();
        if (userNameAttributeName == null || userNameAttributeName.isBlank()) {
            userNameAttributeName = "id";
        }

        return new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("ROLE_USER")),
                attributes,
                userNameAttributeName);
    }

    private String resolveGitHubVerifiedEmail(Map<String, Object> attributes, String accessToken) {
        String publicEmail = (String) attributes.get("email");

        // Query https://api.github.com/user/emails to check verified status and find primary address
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.github.com/user/emails"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "Vantis")
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                List<Map<String, Object>> emails = MAPPER.readValue(
                        response.body(), new TypeReference<List<Map<String, Object>>>() {});

                String primaryVerified = null;
                String anyVerified = null;

                for (Map<String, Object> entry : emails) {
                    boolean verified = Boolean.TRUE.equals(entry.get("verified"));
                    boolean primary = Boolean.TRUE.equals(entry.get("primary"));
                    String emailAddr = (String) entry.get("email");

                    if (verified && emailAddr != null && !emailAddr.isBlank()) {
                        if (primary) {
                            primaryVerified = emailAddr;
                            break;
                        } else if (anyVerified == null) {
                            anyVerified = emailAddr;
                        }
                    }
                }

                String chosen = primaryVerified != null ? primaryVerified : anyVerified;
                if (chosen != null) {
                    return chosen;
                }
            } else {
                log.warn("GitHub emails API responded with status {}", response.statusCode());
            }
        } catch (Exception ex) {
            log.warn("Could not retrieve emails from GitHub API: {}", ex.getMessage());
        }

        // If public email was present and is not a noreply alias
        if (publicEmail != null && !publicEmail.isBlank() && !publicEmail.contains("noreply.github.com")) {
            return publicEmail;
        }

        throw new OAuth2AuthenticationException(
                new OAuth2Error("missing_verified_email"),
                "GitHub account does not have a verified email address.");
    }
}
