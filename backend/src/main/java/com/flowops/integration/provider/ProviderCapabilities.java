package com.flowops.integration.provider;

/**
 * Explicit capability flags for a provider adapter.
 * Used by the UI and anomaly detectors to honestly declare capabilities and degrade gracefully.
 */
public record ProviderCapabilities(
        boolean workflowDiscovery,
        boolean executionHistory,
        boolean nodeExecutionData,
        boolean incrementalSync,
        boolean webhooks,
        boolean tracing,
        boolean outputItemCounts,
        boolean outputStructure,
        boolean errorDetails,
        boolean scheduleDiscovery,
        boolean rateLimits,
        Integer dataRetentionDays
) {
    /**
     * Backwards-compatible 6-argument constructor for legacy callers.
     */
    public ProviderCapabilities(
            boolean workflowDiscovery,
            boolean executionHistory,
            boolean nodeExecutionData,
            boolean incrementalSync,
            boolean webhooks,
            boolean tracing) {
        this(workflowDiscovery, executionHistory, nodeExecutionData, incrementalSync, webhooks, tracing,
                false, false, false, false, false, null);
    }

    public boolean nodeLevelData() {
        return nodeExecutionData;
    }

    public boolean webhookIngestion() {
        return webhooks;
    }
}