package com.flowops.auth;

import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.config.AuthProperties;
import com.flowops.domain.EmailVerificationToken;
import com.flowops.domain.UserAccount;
import com.flowops.repository.EmailVerificationTokenRepository;
import com.flowops.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages email verification tokens, issuance, validation, and delivery.
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private final UserRepository users;
    private final EmailVerificationTokenRepository tokens;
    private final EmailVerificationMailer mailer;
    private final Duration tokenTtl;

    public EmailVerificationService(
            UserRepository users,
            EmailVerificationTokenRepository tokens,
            EmailVerificationMailer mailer,
            AuthProperties authProperties) {
        this.users = users;
        this.tokens = tokens;
        this.mailer = mailer;
        this.tokenTtl = authProperties.verificationTokenTtl();
    }

    @Transactional
    public String createAndSendToken(UserAccount user) {
        Objects.requireNonNull(user, "User must not be null");

        // Invalidate previous unconsumed verification tokens for this user
        Instant now = Instant.now();
        tokens.revokeAllForUser(user.getId(), now);

        String plaintext = OpaqueTokens.mint();
        String hash = OpaqueTokens.hash(plaintext);

        EmailVerificationToken token = EmailVerificationToken.create(
                user.getId(), hash, now.plus(tokenTtl));
        tokens.save(token);

        mailer.sendVerificationEmail(user.getEmail(), plaintext);
        return plaintext;
    }

    @Transactional
    public String verifyEmail(String plaintextToken) {
        if (plaintextToken == null || plaintextToken.isBlank()) {
            throw new ApiException(ErrorCode.VERIFICATION_TOKEN_INVALID);
        }

        String hash = OpaqueTokens.hash(plaintextToken.trim());
        EmailVerificationToken token = tokens.findByTokenHash(hash)
                .orElseThrow(() -> new ApiException(ErrorCode.VERIFICATION_TOKEN_INVALID));

        Instant now = Instant.now();
        if (token.isExpired(now) || token.isConsumed()) {
            throw new ApiException(ErrorCode.VERIFICATION_TOKEN_INVALID);
        }

        int updated = tokens.consumeToken(token.getId(), now);
        if (updated == 0) {
            // Already consumed in a concurrent transaction
            throw new ApiException(ErrorCode.VERIFICATION_TOKEN_INVALID);
        }

        UserAccount user = users.findById(token.getUserId())
                .orElseThrow(() -> new ApiException(ErrorCode.VERIFICATION_TOKEN_INVALID));

        user.markEmailVerified();
        users.save(user);

        log.info("Email verified successfully for user {}", user.getId());
        return user.getEmail();
    }

    @Transactional
    public void resendVerification(String email) {
        if (email == null || email.isBlank()) {
            return;
        }

        String normalized = email.toLowerCase(Locale.ROOT).trim();
        UserAccount user = users.findByEmailIgnoreCase(normalized).orElse(null);

        // Anti-enumeration: if user doesn't exist, return normally
        if (user == null) {
            log.debug("Resend verification requested for non-existent email {}", normalized);
            return;
        }

        // If user is already verified, do not send another verification token
        if (user.isEmailVerified()) {
            log.debug("Resend verification requested for already verified user {}", user.getId());
            return;
        }

        createAndSendToken(user);
    }
}
