package com.flowops.security.oauth2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;

/**
 * Custom OIDC user service for Google OpenID Connect authentication.
 *
 * <p>Strictly validates Google's cryptographic {@code email_verified} assertion.
 * An unverified email address is never accepted.
 */
@Service
public class CustomOidcUserService extends OidcUserService {

    private static final Logger log = LoggerFactory.getLogger(CustomOidcUserService.class);

    @Override
    public OidcUser loadUser(OidcUserRequest userRequest) throws OAuth2AuthenticationException {
        OidcUser oidcUser = super.loadUser(userRequest);

        Boolean emailVerified = oidcUser.getEmailVerified();
        if (emailVerified == null || !emailVerified) {
            log.warn("Rejected Google OIDC login: email_verified is false or missing for sub {}",
                    oidcUser.getSubject());
            throw new OAuth2AuthenticationException(
                    new OAuth2Error("unverified_email"),
                    "Your Google account email is not verified.");
        }

        return oidcUser;
    }
}
