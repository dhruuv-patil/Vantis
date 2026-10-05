package com.flowops.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowops.config.MailProperties;
import com.flowops.integration.delivery.smtp.SmtpException;
import com.flowops.integration.delivery.smtp.SmtpSettings;
import com.flowops.integration.delivery.smtp.SmtpTransport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Unit tests for {@link PasswordResetMailer}: the reset URL carries the plaintext
 * token to the relay, an unconfigured relay is a safe no-op, a transport failure
 * never propagates (so forgot-password keeps its 204 contract), and the plaintext
 * token and SMTP password never reach a log line.
 *
 * <p>No Spring context and no Mockito: delivery goes through a hand-written
 * {@link SmtpTransport} fake that records calls and can be told to throw.
 */
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetMailerTest {

    private static final String SMTP_PASSWORD = "smtp-password-supersecret";

    private static final class FakeTransport implements SmtpTransport {
        final List<SentMail> sends = new ArrayList<>();
        int attempts;
        SmtpException smtpFailure;
        RuntimeException runtimeFailure;

        @Override
        public void testConnection(SmtpSettings settings) {
        }

        @Override
        public void send(SmtpSettings settings, String to, String subject, String body)
                throws SmtpException {
            attempts++;
            if (smtpFailure != null) {
                throw smtpFailure;
            }
            if (runtimeFailure != null) {
                throw runtimeFailure;
            }
            sends.add(new SentMail(settings, to, subject, body));
        }

        record SentMail(SmtpSettings settings, String to, String subject, String body) {
        }
    }

    @Test
    void sendsResetEmailWhoseUrlCarriesThePlaintextToken() {
        FakeTransport transport = new FakeTransport();
        PasswordResetMailer mailer = new PasswordResetMailer(
                transport, mail("smtp.example.com"));

        mailer.sendResetEmail("user@example.com", "plaintext-token-ABC123");

        assertThat(transport.sends).hasSize(1);
        FakeTransport.SentMail sent = transport.sends.get(0);
        assertThat(sent.to()).isEqualTo("user@example.com");
        assertThat(sent.subject()).isEqualTo("Reset your Vantis password");
        // The reset link is its own line: the token rides only as the query
        // parameter of {appBaseUrl}/reset-password?token=…
        String linkLine = sent.body().lines()
                .filter(l -> l.contains("/reset-password?token="))
                .findFirst()
                .orElseThrow();
        assertThat(linkLine)
                .isEqualTo("http://localhost:3000/reset-password?token=plaintext-token-ABC123");

        // Settings are derived from the application configuration, not integration
        // credentials.
        SmtpSettings settings = sent.settings();
        assertThat(settings.host()).isEqualTo("smtp.example.com");
        assertThat(settings.port()).isEqualTo(587);
        assertThat(settings.fromAddress()).isEqualTo("no-reply@example.com");
        assertThat(settings.fromName()).isEqualTo("Vantis");
        assertThat(settings.encryption()).isEqualTo(SmtpSettings.Encryption.STARTTLS);
    }

    @Test
    void unconfiguredRelayIsASilentNoOpForTheRequest() {
        FakeTransport transport = new FakeTransport();
        PasswordResetMailer mailer = new PasswordResetMailer(transport, mail(""));

        mailer.sendResetEmail("user@example.com", "plaintext-token-ABC123");

        assertThat(transport.sends).isEmpty();
    }

    @Test
    void transportFailureNeverPropagates() {
        FakeTransport transport = new FakeTransport();
        transport.smtpFailure = new SmtpException(
                SmtpException.Codes.TIMEOUT, "not responding within timeout", true);
        PasswordResetMailer mailer = new PasswordResetMailer(
                transport, mail("smtp.example.com"));

        // Must not throw: a delivery failure cannot turn the generic 204 into a 500.
        mailer.sendResetEmail("user@example.com", "plaintext-token-ABC123");

        assertThat(transport.attempts).isEqualTo(1); // the attempt happened
    }

    @Test
    void runtimeFailureNeverPropagates() {
        FakeTransport transport = new FakeTransport();
        transport.runtimeFailure = new IllegalStateException("relay exploded");
        PasswordResetMailer mailer = new PasswordResetMailer(
                transport, mail("smtp.example.com"));

        mailer.sendResetEmail("user@example.com", "plaintext-token-ABC123");

        assertThat(transport.attempts).isEqualTo(1);
    }

    @Test
    void neverLogsThePlaintextTokenOrTheSmtpPassword(CapturedOutput output) {
        FakeTransport transport = new FakeTransport();
        transport.smtpFailure = new SmtpException(SmtpException.Codes.AUTH, "rejected", false);
        PasswordResetMailer mailer = new PasswordResetMailer(
                transport, mail("smtp.example.com"));

        mailer.sendResetEmail("user@example.com", "TOP-SECRET-RESET-TOKEN");

        assertThat(output.getAll()).doesNotContain("TOP-SECRET-RESET-TOKEN");
        assertThat(output.getAll()).doesNotContain(SMTP_PASSWORD);
    }

    private static MailProperties mail(String host) {
        return new MailProperties(
                host,
                587,
                "reset-bot@example.com",
                SMTP_PASSWORD,
                "STARTTLS",
                "no-reply@example.com",
                "Vantis",
                "http://localhost:3000");
    }
}