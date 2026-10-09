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
 * <p>Zapier's public API does not expose Zap discovery or run history. This provider
 * implements the <em>connect + test</em> path only (validating a webhook URL the
 * user creates in a "Catch Hook" Zap). It reports {@code executionHistory=false},
 * {@code workflowDiscovery=false}, so the sync scheduler skips it entirely and the
 * UI shows it as a one-way event sink. For telemetry from Zapier, users configure
 * their Zaps to POST to the FlowOps outbound webhook provider instead — a standard
 * webhook integration, not a Zapier-specific one.</p>
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
        return "Connect Zapier zaps to receive FlowOps events. Run history is not "
                + "exposed by Zapier's public API; use the outbound webhook integration "
                + "to send events from Zapier into FlowOps.";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                false,  // workflowDiscovery
                false,  // executionHistory
                false,  // nodeExecutionData
                false,  // incrementalSync
                true,   // webhooks (one-way: FlowOps → Zapier via webhook URL)
                false,  // tracing
                false,  // outputItemCounts
                false,  // outputStructure
                false,  // errorDetails
                false,  // scheduleDiscovery
                false,  // rateLimits
                null    // dataRetentionDays
        );
    }

    @Override
    public List<CredentialField> credentialFields() {
        // The Zapier client reads a single secret: a webhook URL the user pastes from
        // a "Catch Hook" Zap (Zapier's public API exposes no API-key authentication).
        return List.of(
                new CredentialField("webhookUrl", "Zapier webhook URL", true,
                        "https://hooks.zapier.com/hooks/catch/…",
                        "https://hooks.zapier.com/hooks/catch/…",
                        "Create a Catch Hook Zap and paste its webhook URL. Stored encrypted; "
                                + "never shown again."));
    }

    @Override
    public ConnectionTestResult testConnection(IntegrationContext context) {
        try {
            client.testWebhook(context);
            return ConnectionTestResult.success("Webhook accepted the test event");
        } catch (ProviderAuthenticationException e) {
            return ConnectionTestResult.failure("Authentication failed: " + e.getMessage());
        } catch (ProviderConfigurationException e) {
            return ConnectionTestResult.failure("Configuration error: " + e.getMessage());
        } catch (ProviderUnavailableException e) {
            return ConnectionTestResult.failure("Provider unavailable: " + e.getMessage());
        } catch (Exception e) {
            log.warn("Zapier test connection failed", e);
            return ConnectionTestResult.failure("Connection test failed: " + e.getMessage());
        }
    }

    @Override
    public List<ExternalWorkflow> discoverWorkflows(IntegrationContext context) {
        // Not supported by Zapier's public API.
        return List.of();
    }

    @Override
    public ExecutionPage<ExternalExecution> fetchExecutions(
            IntegrationContext context, SyncCursor cursor) {
        // Not supported by Zapier's public API. Return empty to avoid scheduler errors.
        return ExecutionPage.empty();
    }

    @Override
    public List<ExternalNodeExecution> fetchNodeExecutions(
            IntegrationContext context, ExternalExecution execution) {
        return List.of();
    }
}