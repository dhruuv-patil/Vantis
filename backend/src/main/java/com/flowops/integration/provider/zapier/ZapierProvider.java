package com.flowops.integration.provider.zapier;

import com.flowops.integration.provider.ConnectionTestResult;
import com.flowops.integration.provider.CredentialField;
import com.flowops.integration.provider.ExecutionPage;
import com.flowops.integration.provider.ExternalExecution;
import com.flowops.integration.provider.ExternalNodeExecution;
import com.flowops.integration.provider.ExternalWorkflow;
import com.flowops.integration.provider.IntegrationContext;
import com.flowops.integration.provider.IntegrationType;
import com.flowops.integration.provider.ProviderAuthenticationException;
import com.flowops.integration.provider.ProviderCapabilities;
import com.flowops.integration.provider.ProviderConfigurationException;
import com.flowops.integration.provider.ProviderUnavailableException;
import com.flowops.integration.provider.SyncCursor;
import com.flowops.integration.provider.WorkflowProvider;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Zapier workflow provider — honest about capabilities.
 *
 * <p>Zapier's public API does not expose Zap discovery or run history. Monitoring is
 * achieved via inbound webhook instrumentation: users configure a Webhooks by Zapier
 * POST step in their Zaps that targets {@code /api/webhooks/zapier/{integrationId}}
 * with HMAC-SHA256 signature verification.</p>
 */
@Component
public class ZapierProvider implements WorkflowProvider {

    private static final Logger log = LoggerFactory.getLogger(ZapierProvider.class);

    private final ZapierClient client;

    public ZapierProvider(ZapierClient client) {
        this.client = client;
    }

    @Override
    public IntegrationType type() {
        return IntegrationType.ZAPIER;
    }

    @Override
    public String displayName() {
        return "Zapier";
    }

    @Override
    public String description() {
        return "Zapier automation platform. Monitored via inbound webhook instrumentation: "
                + "configure a Webhooks by Zapier action in your Zap to POST execution telemetry "
                + "to /api/webhooks/zapier/{integrationId} with HMAC-SHA256 authentication.";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                false,  // workflowDiscovery (Zapier API does not expose user Zaps)
                false,  // executionHistory (Polling unsupported; uses inbound webhooks)
                false,  // nodeExecutionData (Step-level execution data is not exposed unless provided in webhook)
                false,  // incrementalSync
                true,   // webhooks (Inbound instrumentation + outbound Catch Hook)
                false,  // tracing
                false,  // outputItemCounts (Not polled via Zapier API)
                false,  // outputStructure (Not polled via Zapier API)
                false,  // errorDetails (Not polled via Zapier API)
                false,  // scheduleDiscovery
                false,  // rateLimits (No polling API rate limits)
                null    // dataRetentionDays
        );
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of(
                new CredentialField("webhookSecret", "Webhook HMAC Secret", true,
                        "whsec_...", "whsec_...",
                        "Shared secret for HMAC-SHA256 signature verification of inbound Zapier telemetry. Stored encrypted."),
                new CredentialField("webhookUrl", "Outbound Zapier webhook URL (Optional)", false,
                        "https://hooks.zapier.com/hooks/catch/…",
                        "https://hooks.zapier.com/hooks/catch/…",
                        "Optional: Create a Catch Hook Zap and paste its webhook URL to receive outbound FlowOps notifications."));
    }

    @Override
    public ConnectionTestResult testConnection(IntegrationContext context) {
        if (context == null || context.credentials() == null) {
            return ConnectionTestResult.failure("Missing Zapier credentials");
        }
        String secret = context.credentials().get("webhookSecret");
        String webhookUrl = context.credentials().get("webhookUrl");

        if ((secret == null || secret.isBlank()) && (webhookUrl == null || webhookUrl.isBlank())) {
            return ConnectionTestResult.failure("Please provide either a webhook HMAC secret or an outbound webhook URL");
        }

        if (webhookUrl != null && !webhookUrl.isBlank()) {
            try {
                client.testWebhook(context);
            } catch (Exception e) {
                log.warn("Zapier outbound webhook test failed", e);
                return ConnectionTestResult.failure("Outbound webhook test failed: " + e.getMessage());
            }
        }

        return ConnectionTestResult.success("Zapier configuration valid and ready for telemetry");
    }

    @Override
    public List<ExternalWorkflow> discoverWorkflows(IntegrationContext context) {
        // Not supported by Zapier's public API.
        return List.of();
    }

    @Override
    public ExecutionPage<ExternalExecution> fetchExecutions(
            IntegrationContext context, SyncCursor cursor) {
        // Polling not supported by Zapier's public API. Telemetry arrives via inbound webhooks.
        return ExecutionPage.empty();
    }

    @Override
    public List<ExternalNodeExecution> fetchNodeExecutions(
            IntegrationContext context, ExternalExecution execution) {
        return List.of();
    }
}