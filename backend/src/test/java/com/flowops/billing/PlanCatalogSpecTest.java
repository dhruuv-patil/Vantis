package com.flowops.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowops.billing.model.PlanCatalog;
import com.flowops.billing.model.PlanDefinition;
import com.flowops.billing.model.PlanLimits;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlanCatalogSpecTest {

    @Test
    @DisplayName("FREE plan limits match exact literal spec numbers")
    void freePlanLimitsMatchSpec() {
        Optional<PlanDefinition> freeOpt = PlanCatalog.get("FREE");
        assertThat(freeOpt).isPresent();
        PlanLimits limits = freeOpt.get().limits();

        assertThat(limits.activeWorkflows()).isEqualTo(3);
        assertThat(limits.executionsPerMonth()).isEqualTo(1000);
        assertThat(limits.aiGenerations()).isEqualTo(5);
        assertThat(limits.aiInvestigationsMonthly()).isEqualTo(0);
        assertThat(limits.aiInvestigationsLifetimeTrial()).isEqualTo(2);
        assertThat(limits.seats()).isEqualTo(1);
        assertThat(limits.workspaces()).isEqualTo(1);
        assertThat(limits.historyRetentionDays()).isEqualTo(3);
        assertThat(limits.workflowVersionsKept()).isEqualTo(3);
        assertThat(limits.maxAnomalyMode()).isEqualTo("basic");
        assertThat(limits.allowedChannels()).containsExactlyInAnyOrder("EMAIL", "IN_APP", "WEBHOOK");
    }

    @Test
    @DisplayName("PRIVATE_BETA plan limits match exact literal spec numbers")
    void privateBetaPlanLimitsMatchSpec() {
        Optional<PlanDefinition> betaOpt = PlanCatalog.get("PRIVATE_BETA");
        assertThat(betaOpt).isPresent();
        PlanLimits limits = betaOpt.get().limits();

        assertThat(limits.activeWorkflows()).isEqualTo(10);
        assertThat(limits.executionsPerMonth()).isEqualTo(50_000);
        assertThat(limits.aiGenerations()).isEqualTo(500);
        assertThat(limits.aiInvestigationsMonthly()).isEqualTo(50);
        assertThat(limits.aiInvestigationsLifetimeTrial()).isEqualTo(0);
        assertThat(limits.seats()).isEqualTo(10);
        assertThat(limits.workspaces()).isEqualTo(5);
        assertThat(limits.historyRetentionDays()).isEqualTo(30);
        assertThat(limits.workflowVersionsKept()).isEqualTo(30);
        assertThat(limits.maxAnomalyMode()).isEqualTo("full");
        assertThat(limits.allowedChannels()).containsExactlyInAnyOrder(
                "EMAIL", "IN_APP", "WEBHOOK", "SLACK", "DISCORD", "TEAMS", "TELEGRAM", "TWILIO", "PAGERDUTY");
    }

    @Test
    @DisplayName("PRO plan limits match exact literal spec numbers")
    void proPlanLimitsMatchSpec() {
        Optional<PlanDefinition> proOpt = PlanCatalog.get("PRO");
        assertThat(proOpt).isPresent();
        PlanLimits limits = proOpt.get().limits();

        assertThat(limits.activeWorkflows()).isEqualTo(100);
        assertThat(limits.executionsPerMonth()).isEqualTo(50_000);
        assertThat(limits.aiGenerations()).isEqualTo(500);
        assertThat(limits.aiInvestigationsMonthly()).isEqualTo(50);
        assertThat(limits.aiInvestigationsLifetimeTrial()).isEqualTo(0);
        assertThat(limits.seats()).isEqualTo(10);
        assertThat(limits.workspaces()).isEqualTo(5);
        assertThat(limits.historyRetentionDays()).isEqualTo(30);
        assertThat(limits.workflowVersionsKept()).isEqualTo(30);
        assertThat(limits.maxAnomalyMode()).isEqualTo("full");
        assertThat(limits.allowedChannels()).containsExactlyInAnyOrder(
                "EMAIL", "IN_APP", "WEBHOOK", "SLACK", "DISCORD", "TEAMS", "TELEGRAM", "TWILIO", "PAGERDUTY");
    }

    @Test
    @DisplayName("BUSINESS plan limits match exact literal spec numbers (finite values, history 90, tunable)")
    void businessPlanLimitsMatchSpec() {
        Optional<PlanDefinition> busOpt = PlanCatalog.get("BUSINESS");
        assertThat(busOpt).isPresent();
        PlanLimits limits = busOpt.get().limits();

        assertThat(limits.activeWorkflows()).isEqualTo(1000);
        assertThat(limits.executionsPerMonth()).isEqualTo(1_000_000);
        assertThat(limits.aiGenerations()).isEqualTo(10_000);
        assertThat(limits.aiInvestigationsMonthly()).isEqualTo(1000);
        assertThat(limits.aiInvestigationsLifetimeTrial()).isEqualTo(0);
        assertThat(limits.seats()).isEqualTo(100);
        assertThat(limits.workspaces()).isEqualTo(50);
        assertThat(limits.historyRetentionDays()).isEqualTo(90);
        assertThat(limits.workflowVersionsKept()).isEqualTo(100);
        assertThat(limits.maxAnomalyMode()).isEqualTo("tunable");
        assertThat(limits.allowedChannels()).containsExactlyInAnyOrder(
                "EMAIL", "IN_APP", "WEBHOOK", "SLACK", "DISCORD", "TEAMS", "TELEGRAM", "TWILIO", "PAGERDUTY", "CUSTOM");
    }
}
