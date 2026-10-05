package com.flowops.integration.delivery.smtp;

import com.flowops.integration.delivery.DeliveryContext;
import com.flowops.integration.delivery.DeliveryResult;
import com.flowops.integration.delivery.NotificationMessage;
import com.flowops.integration.delivery.NotificationProvider;
import com.flowops.integration.provider.ConnectionTestResult;
import com.flowops.integration.provider.CredentialField;
import com.flowops.integration.provider.IntegrationType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Generic SMTP email delivery. Not locked to any vendor: the connect form takes a
 * host, port, credentials, encryption mode, and From address, so Gmail, Microsoft
 * 365, and custom relays are all just host/port/TLS presets. The password lives only
 * in the encrypted credential; it never appears in a response, log, or delivery
 * result. Every operation records integration telemetry (latency, status, error).
 */
@Component
public class SmtpProvider implements NotificationProvider {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    private static final int DEFAULT_PORT = 587;

    private final SmtpTransport transport;

    public SmtpProvider(SmtpTransport transport) {
        this.transport = transport;
    }

    @Override
    public IntegrationType type() {
        return IntegrationType.EMAIL;
    }

    @Override
    public String displayName() {
        return "Email (SMTP)";
    }

    @Override
    public String description() {
        return "Send email from any SMTP relay — Gmail, Microsoft 365, or your own "
                + "server — with encrypted credentials.";
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of(
                new CredentialField("preset", "Provider preset", false,
                        "Custom", "Gmail / Outlook / Custom",
                        "Prefills host, port and TLS for Gmail and Microsoft 365."),
                new CredentialField("host", "SMTP host", false,
                        "smtp.gmail.com", "smtp.gmail.com",
                        "e.g. smtp.gmail.com, smtp.office365.com, or your relay"),
                new CredentialField("port", "Port", false,
                        "587", "587",
                        "Commonly 587 (STARTTLS) or 465 (SSL)"),
                new CredentialField("username", "Username", false,
                        "you@gmail.com", "you@gmail.com",
                        "For Gmail use your full address + an app password"),
                new CredentialField("password", "Password / app password", true,
                        "••••••••", "••••••••",
                        "Gmail: Google Account → Security → App passwords. Stored encrypted."),
                new CredentialField("encryption", "Encryption", false,
                        "STARTTLS", "STARTTLS",
                        "STARTTLS (587) or SSL (465). Use STARTTLS unless the relay insists on SSL."),
                new CredentialField("fromAddress", "From address", false,
                        "alerts@example.com", "alerts@example.com",
                        "The envelope + visible From address"),
                new CredentialField("fromName", "From name (optional)", false,
                        "FlowOps", "FlowOps",
                        "Display name shown beside the From address"));
    }

    @Override
    public String secretKey() {
        return "password";
    }

    @Override
    public ConnectionTestResult testConnection(DeliveryContext context) {
        SmtpSettings settings;
        try {
            settings = toSettings(context.credentials().secrets());
        } catch (IllegalArgumentException | com.flowops.common.error.ApiException invalid) {
            return ConnectionTestResult.failure(invalid.getMessage());
        }
        try {
            transport.testConnection(settings);
            return ConnectionTestResult.success(
                    "Connected and authenticated with " + settings.host());
        } catch (SmtpException failure) {
            return ConnectionTestResult.failure(failure.getMessage());
        }
    }

    @Override
    public DeliveryResult deliver(DeliveryContext context, NotificationMessage message) {
        long start = System.nanoTime();
        SmtpSettings settings;
        try {
            settings = toSettings(context.credentials().secrets());
        } catch (IllegalArgumentException | com.flowops.common.error.ApiException invalid) {
            return failure(DeliveryResult.Code.INVALID_CONFIG, invalid.getMessage(), false, start);
        }

        Object rawTo = message.payload() == null ? null : message.payload().get("to");
        String to = rawTo == null ? "" : String.valueOf(rawTo).trim();
        if (to.isBlank()) {
            return failure(DeliveryResult.Code.INVALID_CONFIG,
                    "Email has no recipient configured on this node.", false, start);
        }
        String subject = subject(message);

        try {
            transport.send(settings, to, subject, message.text() == null ? "" : message.text());
            return DeliveryResult.success(null, elapsed(start));
        } catch (SmtpException failure) {
            return failure(failure.code(), failure.getMessage(), failure.retryable(), start);
        }
    }

    @Override
    public DeliveryResult sendTest(DeliveryContext context) {
        long start = System.nanoTime();
        SmtpSettings settings;
        try {
            settings = toSettings(context.credentials().secrets());
        } catch (IllegalArgumentException | com.flowops.common.error.ApiException invalid) {
            return failure(DeliveryResult.Code.INVALID_CONFIG, invalid.getMessage(), false, start);
        }
        try {
            transport.send(settings, settings.fromAddress(),
                    "FlowOps — test email",
                    "This is a test email from FlowOps. "
                            + "If you can read this, your SMTP integration is working.");
            return DeliveryResult.success(null, elapsed(start));
        } catch (SmtpException failure) {
            return failure(failure.code(), failure.getMessage(), failure.retryable(), start);
        }
    }

    private static String subject(NotificationMessage message) {
        if (message.payload() != null && message.payload().get("subject") instanceof String s
                && !s.isBlank()) {
            return s;
        }
        return "FlowOps notification";
    }

    private static DeliveryResult failure(String code, String message, boolean retryable, long start) {
        return DeliveryResult.failure(code, message, retryable, null, elapsed(start));
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static SmtpSettings toSettings(Map<String, String> config) {
        String host = value(config, "host");
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("SMTP host is required.");
        }
        com.flowops.common.security.SsrfGuard.validateHost(host.trim());
        int port = parseInt(config.get("port"), DEFAULT_PORT);
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("SMTP port is invalid.");
        }
        String fromAddress = value(config, "fromAddress");
        if (fromAddress == null || fromAddress.isBlank()) {
            throw new IllegalArgumentException("SMTP From address is required.");
        }
        return new SmtpSettings(
                host.trim(),
                port,
                value(config, "username"),
                value(config, "password"),
                SmtpSettings.Encryption.fromWire(value(config, "encryption")),
                DEFAULT_TIMEOUT,
                fromAddress.trim(),
                value(config, "fromName"));
    }

    private static String value(Map<String, String> config, String key) {
        String v = config.get(key);
        return v == null ? "" : v.trim();
    }

    private static int parseInt(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException notAnInt) {
            return fallback;
        }
    }
}