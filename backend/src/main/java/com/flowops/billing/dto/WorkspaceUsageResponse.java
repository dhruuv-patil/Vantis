package com.flowops.billing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Real usage figures calculated exclusively from database records for the current billing period.
 */
public record WorkspaceUsageResponse(
        PeriodDto period,
        String plan,
        MetricDto executions,
        MetricDto aiGenerations,
        MetricDto activeWorkflows,
        MetricDto teamMembers) {

    @JsonProperty("workflows")
    public MetricDto workflows() {
        return activeWorkflows;
    }

    public record PeriodDto(Instant start, Instant end) {}

    public record MetricDto(long used, long limit) {
        @JsonProperty("percentage")
        public int percentage() {
            if (limit <= 0) return 0;
            return (int) Math.min(100, Math.round(((double) used / limit) * 100));
        }
    }
}
