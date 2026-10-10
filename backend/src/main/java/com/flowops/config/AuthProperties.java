package com.flowops.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Binds the {@code flowops.auth} tree from {@code application.yml}.
 *
 * <p>Validation of these values (secret strength, cookie safety) happens in
 * {@link AuthPropertiesValidator} at startup so the app fails fast rather than at
 * the first login.
 */
@ConfigurationProperties(prefix = "flowops.auth")
public record AuthProperties(
        String jwtSecret,
        @DefaultValue("flowops") String issuer,
        @DefaultValue("flowops-api") String audience,
        @DefaultValue("15m") Duration accessTokenTtl,
        @DefaultValue("7d") Duration refreshTokenTtl,
        @DefaultValue("1h") Duration resetTokenTtl,
        @DefaultValue("24h") Duration verificationTokenTtl,
        @DefaultValue("12") int bcryptStrength,
        RefreshCookie refreshCookie) {

    public AuthProperties {
        if (refreshCookie == null) {
            refreshCookie = new RefreshCookie("flowops_refresh", "/api/auth", false, "Lax");
        }
    }

    public record RefreshCookie(
            @DefaultValue("flowops_refresh") String name,
            @DefaultValue("/api/auth") String path,
            @DefaultValue("false") boolean secure,
            @DefaultValue("Lax") String sameSite) {
    }
}
