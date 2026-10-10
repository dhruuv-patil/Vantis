package com.flowops.api;

import com.flowops.domain.AnomalySeverity;
import com.flowops.domain.Incident;
import com.flowops.domain.IncidentStatus;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record IncidentResponse(
        UUID id,
        UUID organizationId,
        UUID workflowId,
        UUID executionId,
        UUID anomalyId,
        String fingerprint,
        String title,
        String description,
        AnomalySeverity severity,
        IncidentStatus status,
        Map<String, Object> metadata,
        Instant acknowledgedAt,
        Instant resolvedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static IncidentResponse of(Incident incident) {
        return new IncidentResponse(
                incident.getId(),
                incident.getOrganizationId(),
                incident.getWorkflowId(),
                incident.getExecutionId(),
                incident.getAnomalyId(),
                incident.getFingerprint(),
                incident.getTitle(),
                incident.getDescription(),
                incident.getSeverity(),
                incident.getStatus(),
                incident.getMetadata(),
                incident.getAcknowledgedAt(),
                incident.getResolvedAt(),
                incident.getCreatedAt(),
                incident.getUpdatedAt()
        );
    }
}
