package com.flowops.billing.service;

import com.flowops.config.MailProperties;
import com.flowops.integration.delivery.smtp.SmtpSettings;
import com.flowops.integration.delivery.smtp.SmtpTransport;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Sends billing quota warning and limit reached emails asynchronously.
 * Body content is HTML-escaped to prevent injection.
 */
@Component
public class BillingNoticeMailer {

    private static final Logger log = LoggerFactory.getLogger(BillingNoticeMailer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final SmtpTransport transport;
    private final MailProperties properties;

    public BillingNoticeMailer(SmtpTransport transport, MailProperties properties) {
        this.transport = transport;
        this.properties = properties;
    }

    @Async
    public CompletableFuture<Void> sendBillingEmail(String recipientEmail, String subject, String bodyText) {
        if (!properties.isConfigured()) {
            log.info("Billing notice email to {} skipped: SMTP is not configured.", recipientEmail);
            return CompletableFuture.completedFuture(null);
        }

        try {
            SmtpSettings settings = new SmtpSettings(
                    properties.host(),
                    properties.port(),
                    properties.username(),
                    properties.password(),
                    SmtpSettings.Encryption.fromWire(properties.encryption()),
                    TIMEOUT,
                    properties.fromAddress(),
                    properties.fromName());

            String escapedBody = HtmlUtils.htmlEscape(bodyText).replace("\n", "<br/>");
            String htmlContent = """
                    <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; line-height: 1.5; color: #111;">
                      <h2>Vantis Billing Notice</h2>
                      <p>%s</p>
                      <hr style="border: none; border-top: 1px solid #eaeaea; margin: 20px 0;"/>
                      <p style="font-size: 12px; color: #666;">Manage your plan and usage in your Vantis workspace settings.</p>
                    </div>
                    """.formatted(escapedBody);

            transport.send(settings, recipientEmail, subject, htmlContent);
            log.info("Sent billing notice email to {} subject='{}'", recipientEmail, subject);
        } catch (Exception e) {
            log.warn("Failed to deliver billing notice email to {}: {}", recipientEmail, e.getMessage());
        }

        return CompletableFuture.completedFuture(null);
    }
}
