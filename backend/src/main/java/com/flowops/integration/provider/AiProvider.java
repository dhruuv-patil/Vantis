package com.flowops.integration.provider;

import java.util.List;

/**
 * SPI for AI capability providers (OpenAI, Anthropic).
 */
public interface AiProvider extends WorkflowProvider {

    /** Model listing for the provider. */
    List<String> listModels(IntegrationContext context);

    /** Generate text/completion. */
    String generateContent(IntegrationContext context, String prompt, String model);

    /** Test connection. */
    ConnectionTestResult testConnection(IntegrationContext context);

    @Override
    default List<ExternalWorkflow> discoverWorkflows(IntegrationContext context) {
        return List.of();
    }

    @Override
    default ExecutionPage<ExternalExecution> fetchExecutions(IntegrationContext context, SyncCursor cursor) {
        return ExecutionPage.empty();
    }

    @Override
    default List<ExternalNodeExecution> fetchNodeExecutions(IntegrationContext context, ExternalExecution execution) {
        return List.of();
    }

    @Override
    default ProviderCapabilities capabilities() {
        return new ProviderCapabilities(false, false, false, false, false, false);
    }
}
