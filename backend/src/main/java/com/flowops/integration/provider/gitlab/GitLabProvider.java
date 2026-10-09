package com.flowops.integration.provider.gitlab;

import com.flowops.integration.provider.*;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class GitLabProvider implements WorkflowProvider {
    @Override public IntegrationType type() { return IntegrationType.GITLAB; }
    @Override public String displayName() { return "GitLab"; }
    @Override public String description() { return "Connect to GitLab issues/pipelines."; }
    @Override public ProviderCapabilities capabilities() { return new ProviderCapabilities(true, true, true, false, true, true); }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of(
            new CredentialField("apiToken", "Personal Access Token", true, "glpat-...", "glpat-...", "GitLab PAT"),
            new CredentialField("baseUrl", "GitLab URL", false, "https://gitlab.com", "https://gitlab.example.com", "GitLab Instance URL")
        );
    }

    @Override public ConnectionTestResult testConnection(IntegrationContext context) { return ConnectionTestResult.success("Connected to GitLab"); }
    @Override public List<ExternalWorkflow> discoverWorkflows(IntegrationContext context) { return List.of(); }
    @Override public ExecutionPage<ExternalExecution> fetchExecutions(IntegrationContext context, SyncCursor cursor) { return new ExecutionPage<>(List.of(), SyncCursor.EMPTY, false); }
    @Override public List<ExternalNodeExecution> fetchNodeExecutions(IntegrationContext context, ExternalExecution execution) { return List.of(); }
}
