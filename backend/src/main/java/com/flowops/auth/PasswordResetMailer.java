package com.flowops.auth;

import com.flowops.config.MailProperties;
import com.flowops.integration.delivery.smtp.SmtpException;
import com.flowops.integration.delivery.smtp.SmtpSettings;
import com.flowops.integration.delivery.smtp.SmtpTransport;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Sends the password-reset email through the application's SMTP relay
 * ({@code flowops.mail}).
 *
 * <p>Reuses the same dependency-free {@link SmtpTransport} as the workflow email
 * integration, but grounds it in {@link MailProperties} (operator environment
 * config) instead of a customer's encrypted integration credentials. The plaintext
 * token reaches the email only as the query parameter of a link
 * ({@code {appBaseUrl}/reset-password?token=…}); it is never logged, never
 * returned, and never stored here.
 *
 * <p>Delivery must never change the shape of the forgot-password response: a
 * disabled relay or a failed send still returns 204, so a known account stays
 * indistinguishable from an unknown one by status code. Failures are logged at
 * WARN without the token or the SMTP credentials.
 */
@Component
public class PasswordResetMailer {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetMailer.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final SmtpTransport transport;
    private final MailProperties properties;

    public PasswordResetMailer(SmtpTransport transport, MailProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    /**
     * Emails a single-use reset link to {@code recipient}. Safe no-op when the
     * relay is not configured; never throws, so an SMTP outage cannot surface as
     * anything other than the generic 204 the controller always returns.
     */
    public void sendResetEmail(String recipient, String plaintextToken) {
        if (!properties.isConfigured()) {
            log.warn("Password reset email not sent: SMTP is not configured "
                    + "(set SMTP_HOST and SMTP_FROM_ADDRESS to enable).");
            return;
        }

        SmtpSettings settings = new SmtpSettings(
                properties.host(),
                properties.port(),
                properties.username(),
                properties.password(),
                SmtpSettings.Encryption.fromWire(properties.encryption()),
                TIMEOUT,
                properties.fromAddress(),
                properties.fromName());

        String resetUrl = properties.appBaseUrl() + "/reset-password?token=" + plaintextToken;

        try {
            transport.send(settings, recipient, "Reset your Vantis password",
                    resetEmailBody(recipient, resetUrl));
            log.info("Password reset email sent for {}", recipient);
        } catch (SmtpException failure) {
            // The status code is sacred: revealing delivery state would turn the
            // generic 204 into an account-enumeration oracle. The exception carries
            // relay outcome only — never the token, never the SMTP password — so
            // logging it is safe.
            log.warn("Password reset email failed to send (code={}, to={}): {}",
                    failure.code(), recipient, failureMessage(failure));
        } catch (RuntimeException failure) {
            // A buggy transport must not become a 500 on a public endpoint that is
            // contractually anonymous. Log the fault (no token, no credentials).
            log.error("Password reset email failed unexpectedly for {}", recipient, failure);
        }
    }

    private static String resetEmailBody(String recipient, String resetUrl) {
        return "We received a request to reset the password for the Vantis account "
                + recipient + ".\n\n"
                + "Open the link below to choose a new password. The link is single-use "
                + "and expires after 1 hour:\n\n"
                + resetUrl + "\n\n"
                + "If you did not ask to reset your password, you can safely ignore this "
                + "email. Your password will not change unless you use the link above.";
    }

    private static String failureMessage(SmtpException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.code()
                : failure.code() + " — " + message;
    }
}