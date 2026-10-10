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
 * Sends the email-verification email through the application's SMTP relay
 * ({@code flowops.mail}).
 *
 * <p>Reuses {@link SmtpTransport} and {@link MailProperties}.
 * The plaintext token reaches the email only as the query parameter of a link
 * ({@code {appBaseUrl}/verify-email?token=…}); it is never logged, never returned
 * in an API, and never stored here.
 *
 * <p>Safe no-op when the relay is not configured; never throws or crashes registration.
 */
@Component
public class EmailVerificationMailer {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationMailer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final SmtpTransport transport;
    private final MailProperties properties;

    public EmailVerificationMailer(SmtpTransport transport, MailProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    public void sendVerificationEmail(String recipient, String plaintextToken) {
        if (!properties.isConfigured()) {
            log.warn("Email verification not sent: SMTP is not configured "
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

        String verificationUrl = properties.appBaseUrl() + "/verify-email?token=" + plaintextToken;

        try {
            transport.send(settings, recipient, "Verify your Vantis email address",
                    verificationEmailBody(recipient, verificationUrl));
            log.info("Email verification sent for {}", recipient);
        } catch (SmtpException failure) {
            log.warn("Email verification failed to send (code={}, to={}): {}",
                    failure.code(), recipient, failureMessage(failure));
        } catch (RuntimeException failure) {
            log.error("Email verification failed unexpectedly for {}", recipient, failure);
        }
    }

    private static String verificationEmailBody(String recipient, String verificationUrl) {
        return "Welcome to Vantis!\n\n"
                + "Please verify your email address (" + recipient + ") to complete your "
                + "registration and access your workspace:\n\n"
                + verificationUrl + "\n\n"
                + "This verification link is single-use and expires after 24 hours.\n\n"
                + "If you did not sign up for a Vantis account, you can safely ignore this email.\n"
                + "Your account will remain inactive until verified.";
    }

    private static String failureMessage(SmtpException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.code()
                : failure.code() + " — " + message;
    }
}
