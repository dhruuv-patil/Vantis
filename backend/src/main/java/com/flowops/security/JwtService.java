package com.flowops.security;

import com.flowops.config.AuthProperties;
import com.flowops.domain.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Mints and verifies the HS256 access token (contract §1.1).
 *
 * <p>Verification is stateless — no database access — because everything an
 * authorization decision needs is in the claims. The accepted tradeoff is that a
 * logged-out / role-changed / org-switched token stays valid until {@code exp}, a
 * window of at most the 15-minute TTL.
 */
@Service
public class JwtService {

    /** Contract §1.1: accept 30 s of clock skew on {@code exp}/{@code iat}. */
    private static final long CLOCK_SKEW_SECONDS = 30;

    private final SecretKey signingKey;
    private final String issuer;
    private final String audience;
    private final long accessTokenTtlSeconds;

    public JwtService(AuthProperties properties) {
        // AuthPropertiesValidator has already guaranteed a >= 32-byte secret.
        this.signingKey = Keys.hmacShaKeyFor(
                properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
        this.issuer = properties.issuer();
        this.audience = properties.audience();
        this.accessTokenTtlSeconds = properties.accessTokenTtl().toSeconds();
    }

    public long accessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    /** Signs an access token for the given identity + tenant context. */
    public String issueAccessToken(
            UUID userId, UUID sessionId, UUID organizationId, Role role, String email) {

        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiry = now.plusSeconds(accessTokenTtlSeconds);

        return Jwts.builder()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(userId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim("tokenUse", "access")
                .claim("sid", sessionId.toString())
                .claim("orgId", organizationId.toString())
                .claim("role", role.name())
                .claim("email", email)
                .claim("emailVerified", true)
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Verifies signature, {@code exp}, {@code iss}, {@code aud} and
     * {@code tokenUse}, returning the principal. Throws {@link JwtValidationException}
     * — expired vs invalid — so the filter can pick the right error code.
     */
    public FlowOpsPrincipal verifyAccessToken(String token) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(issuer)
                    .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw JwtValidationException.expired(ex);
        } catch (JwtException | IllegalArgumentException ex) {
            throw JwtValidationException.invalid("Access token is invalid.", ex);
        }

        if (!hasAudience(claims)) {
            throw JwtValidationException.invalid("Wrong token audience.");
        }

        if (!"access".equals(claims.get("tokenUse", String.class))) {
            throw JwtValidationException.invalid("Wrong token use.");
        }

        try {
            UUID userId = UUID.fromString(claims.getSubject());
            UUID sessionId = UUID.fromString(claims.get("sid", String.class));
            UUID organizationId = UUID.fromString(claims.get("orgId", String.class));
            Role role = Role.valueOf(claims.get("role", String.class));
            String email = claims.get("email", String.class);
            return new FlowOpsPrincipal(userId, sessionId, organizationId, role, email);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw JwtValidationException.invalid("Malformed token claims.", ex);
        }
    }

    /**
     * Checks the {@code aud} claim without depending on whether the JWT library
     * models it as a string or a set — it is read as a raw claim and both shapes
     * are accepted.
     */
    private boolean hasAudience(Claims claims) {
        Object raw = claims.get("aud");
        if (raw instanceof String single) {
            return audience.equals(single);
        }
        if (raw instanceof Iterable<?> values) {
            for (Object value : values) {
                if (audience.equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }
}
