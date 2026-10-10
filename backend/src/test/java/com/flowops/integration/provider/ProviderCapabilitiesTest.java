package com.flowops.integration.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.flowops.integration.provider.make.MakeClient;
import com.flowops.integration.provider.make.MakeProvider;
import com.flowops.integration.provider.n8n.N8nClient;
import com.flowops.integration.provider.n8n.N8nProvider;
import com.flowops.integration.provider.zapier.ZapierProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProviderCapabilitiesTest {

    @Test
    @DisplayName("N8nProvider honestly declares node-level data, item counts, and output structure support")
    void n8nCapabilitiesHonesty() {
        N8nClient client = mock(N8nClient.class);
        N8nProvider provider = new N8nProvider(client);
        ProviderCapabilities caps = provider.capabilities();

        assertThat(caps.workflowDiscovery()).isTrue();
        assertThat(caps.executionHistory()).isTrue();
        assertThat(caps.nodeExecutionData()).isTrue();
        assertThat(caps.nodeLevelData()).isTrue();
        assertThat(caps.incrementalSync()).isTrue();
        assertThat(caps.outputItemCounts()).isTrue();
        assertThat(caps.outputStructure()).isTrue();
        assertThat(caps.errorDetails()).isTrue();
        assertThat(caps.scheduleDiscovery()).isTrue();
        assertThat(caps.rateLimits()).isFalse(); // n8n does not expose standard rate limit headers
    }

    @Test
    @DisplayName("MakeProvider honestly declares lack of node-level data and item counts, but declares error details and rate limits")
    void makeCapabilitiesHonesty() {
        MakeClient client = mock(MakeClient.class);
        MakeProvider provider = new MakeProvider(client);
        ProviderCapabilities caps = provider.capabilities();

        assertThat(caps.workflowDiscovery()).isTrue();
        assertThat(caps.executionHistory()).isTrue();
        assertThat(caps.nodeExecutionData()).isFalse(); // Make public API has no node execution endpoint
        assertThat(caps.nodeLevelData()).isFalse();
        assertThat(caps.incrementalSync()).isFalse();
        assertThat(caps.outputItemCounts()).isFalse(); // Not available in Make scenario execution list
        assertThat(caps.outputStructure()).isFalse();
        assertThat(caps.errorDetails()).isTrue(); // Scenario-level error details available
        assertThat(caps.scheduleDiscovery()).isFalse();
        assertThat(caps.rateLimits()).isTrue(); // Make has rate limiting with 429
        assertThat(caps.dataRetentionDays()).isEqualTo(30);
    }

    @Test
    @DisplayName("ZapierProvider honestly declares lack of execution history and node data")
    void zapierCapabilitiesHonesty() {
        com.flowops.integration.provider.zapier.ZapierClient client = mock(com.flowops.integration.provider.zapier.ZapierClient.class);
        ZapierProvider provider = new ZapierProvider(client);
        ProviderCapabilities caps = provider.capabilities();

        assertThat(caps.workflowDiscovery()).isFalse();
        assertThat(caps.executionHistory()).isFalse(); // Zapier public API does not expose run history
        assertThat(caps.nodeExecutionData()).isFalse();
        assertThat(caps.nodeLevelData()).isFalse();
        assertThat(caps.incrementalSync()).isFalse();
        assertThat(caps.outputItemCounts()).isFalse();
        assertThat(caps.outputStructure()).isFalse();
        assertThat(caps.errorDetails()).isFalse();
        assertThat(caps.scheduleDiscovery()).isFalse();
        assertThat(caps.webhooks()).isTrue();
    }

    @Test
    @DisplayName("ProviderCapabilities backward-compatible 6-arg constructor defaults new flags safely")
    void backwardCompatibleConstructor() {
        ProviderCapabilities legacy = new ProviderCapabilities(
                true, true, true, true, false, false);

        assertThat(legacy.workflowDiscovery()).isTrue();
        assertThat(legacy.executionHistory()).isTrue();
        assertThat(legacy.nodeExecutionData()).isTrue();
        assertThat(legacy.incrementalSync()).isTrue();
        assertThat(legacy.webhooks()).isFalse();
        assertThat(legacy.tracing()).isFalse();
        // New flags degrade honestly to false/null
        assertThat(legacy.outputItemCounts()).isFalse();
        assertThat(legacy.outputStructure()).isFalse();
        assertThat(legacy.errorDetails()).isFalse();
        assertThat(legacy.scheduleDiscovery()).isFalse();
        assertThat(legacy.rateLimits()).isFalse();
        assertThat(legacy.dataRetentionDays()).isNull();
    }
}
