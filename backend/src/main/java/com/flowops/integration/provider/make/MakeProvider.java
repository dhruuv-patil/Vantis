package com.flowops.integration.provider.make;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Make (formerly Integromat) workflow provider adapter.
 * Uses Make public REST API v2.
 */
@Component
public class MakeProvider implements WorkflowProvider {

    private static final Logger log = LoggerFactory.getLogger(MakeProvider.class);

    private final MakeClient client;

    public MakeProvider(MakeClient client) {
        this.client = client;
    }

    @Override
    public IntegrationType type() {
        return IntegrationType.MAKE;
    }

    @Override
    public String displayName() {
        return "Make";
    }

    @Override
    public String description() {
        return "Make (formerly Integromat) workflow automation platform";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                true,   // workflowDiscovery
                true,   // executionHistory
                false,  // nodeExecutionData (Make's public API doesn't expose step data)
                false,  // incrementalSync (no cursor; full-page fetch with idempotency)
                false,  // webhooks
                false,  // tracing
                false,  // outputItemCounts (Make public API does not provide item counts)
                false,  // outputStructure (Make public API does not expose step payloads)
                true,   // errorDetails (Scenario-level error message is reported)
                false,  // scheduleDiscovery
                true,   // rateLimits (Make enforces HTTP 429 with rate limits)
                30      // dataRetentionDays (30-day default run history on Make)
        );
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of(
                new CredentialField("baseUrl", "Base URL", false,
                        "https://eu1.make.com", "https://eu1.make.com",
                        "Your Make region URL (eu1 / us1 / …)"),
                new CredentialField("apiToken", "API Token", true,
                        "secret_xxx", "secret_xxx",
                        "Make API token from Settings → API. Stored encrypted."));
    }

    @Override
    public ConnectionTestResult testConnection(IntegrationContext context) {
        try {
            client.listScenarios(context, 1);
            return ConnectionTestResult.success("Connection successful");
        } catch (ProviderAuthenticationException e) {
            return ConnectionTestResult.failure("Authentication failed: " + e.getMessage());
        } catch (ProviderConfigurationException e) {
            return ConnectionTestResult.failure("Configuration error: " + e.getMessage());
        } catch (ProviderUnavailableException e) {
            return ConnectionTestResult.failure("Provider unavailable: " + e.getMessage());
        } catch (Exception e) {
            log.warn("Make test connection failed", e);
            return ConnectionTestResult.failure("Connection test failed: " + e.getMessage());
        }
    }

    @Override
    public List<ExternalWorkflow> discoverWorkflows(IntegrationContext context) {
        JsonNode response = client.listScenarios(context, 250);
        return MakeWorkflowMapper.toExternal(response);
    }

    @Override
    public ExecutionPage<ExternalExecution> fetchExecutions(
            IntegrationContext context, SyncCursor cursor) {
        // Make's public API doesn't expose a cursor; we fetch a bounded page each sync.
        // Idempotency on execution_events (integration_id + workflow_external_id + execution_external_id)
        // keeps re-syncs cheap and duplicate-free.
        final int limit = 50;
        JsonNode response = client.listExecutions(context, limit);
        List<ExternalExecution> executions = MakeExecutionMapper.toExternal(response);
        // No cursor from Make → always EMPTY, no hasMore (client treats this as a single page)
        return new ExecutionPage<>(
                executions, SyncCursor.EMPTY, false);
    }

    @Override
    public List<ExternalNodeExecution> fetchNodeExecutions(
            IntegrationContext context, ExternalExecution execution) {
        // Make's public API does not expose node/step execution data.
        // Return empty list so the sync loop can continue without failing.
        return List.of();
    }
}