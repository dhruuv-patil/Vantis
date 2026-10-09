package com.flowops.integration.provider.n8n;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * n8n workflow provider adapter.
 * Uses n8n public REST API v1.
 */
@Component
public class N8nProvider implements WorkflowProvider {

    private static final Logger log =
            LoggerFactory.getLogger(N8nProvider.class);

    private final N8nClient client;

    public N8nProvider(N8nClient client) {
        this.client = client;
    }

    @Override
    public IntegrationType type() {
        return IntegrationType.N8N;
    }

    @Override
    public String displayName() {
        return "n8n";
    }

    @Override
    public String description() {
        return "n8n workflow automation platform";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(
                true,   // workflowDiscovery
                true,   // executionHistory
                true,   // nodeExecutionData
                true,   // incrementalSync
                true,   // webhooks
                false,  // tracing
                true,   // outputItemCounts
                true,   // outputStructure
                true,   // errorDetails
                true,   // scheduleDiscovery
                false,  // rateLimits
                null    // dataRetentionDays
        );
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of(
                new CredentialField("baseUrl", "Base URL", false,
                        "https://n8n.example.com", "https://n8n.example.com",
                        "Your n8n instance URL"),
                new CredentialField("apiKey", "API Key", true,
                        "eyJhbGciOi...", "eyJhbGciOi...",
                        "n8n API key from Settings → API"));
    }

    @Override
    public ConnectionTestResult testConnection(
            IntegrationContext context) {

        try {
            client.listWorkflows(context, 1);

            return ConnectionTestResult.success(
                    "Connection successful");

        } catch (ProviderAuthenticationException e) {

            return ConnectionTestResult.failure(
                    "Authentication failed: "
                            + e.getMessage());

        } catch (ProviderConfigurationException e) {

            return ConnectionTestResult.failure(
                    "Configuration error: "
                            + e.getMessage());

        } catch (ProviderUnavailableException e) {

            return ConnectionTestResult.failure(
                    "Provider unavailable: "
                            + e.getMessage());

        } catch (Exception e) {

            log.warn(
                    "n8n test connection failed",
                    e);

            return ConnectionTestResult.failure(
                    "Connection test failed: "
                            + e.getMessage());
        }
    }

    @Override
    public List<ExternalWorkflow> discoverWorkflows(
            IntegrationContext context) {

        JsonNode response =
                client.listWorkflows(context, 250);

        return N8nWorkflowMapper.toExternal(response);
    }

    @Override
    public ExecutionPage<ExternalExecution> fetchExecutions(
            IntegrationContext context,
            SyncCursor cursor) {

        final int limit = 50;

        JsonNode response =
                client.listExecutions(
                        context,
                        limit,
                        cursor);

        List<ExternalExecution> executions =
                N8nExecutionMapper.toExternal(response);

        /*
         * n8n uses an opaque pagination cursor.
         *
         * The cursor MUST come from n8n's `nextCursor` field.
         * It must NOT be generated from the last execution ID.
         */
        SyncCursor nextCursor =
                N8nExecutionMapper.nextCursor(response);

        /*
         * n8n's nextCursor determines whether another page exists.
         *
         * If n8n does not return a nextCursor, the current page is the
         * final page.
         */
        boolean hasMore =
                !nextCursor.isEmpty();

        return new ExecutionPage<>(
                executions,
                nextCursor,
                hasMore);
    }

    @Override
    public List<ExternalNodeExecution> fetchNodeExecutions(
            IntegrationContext context,
            ExternalExecution execution) {

        JsonNode response =
                client.getExecutionData(
                        context,
                        execution.externalId());

        return N8nNodeExecutionMapper.toExternal(
                response,
                execution.externalId(),
                execution.workflowExternalId());
    }
}