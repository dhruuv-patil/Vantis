package com.flowops.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "incidents")
public class Incident {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "workflow_id", nullable = false, updatable = false)
    private UUID workflowId;

    @Column(name = "execution_id")
    private UUID executionId;

    @Column(name = "anomaly_id")
    private UUID anomalyId;

    @Column(name = "fingerprint", length = 128)
    private String fingerprint;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 16)
    private AnomalySeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IncidentStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata")
    private Map<String, Object> metadata;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Incident() {
        // JPA
    }

    public static Incident open(
            UUID organizationId,
            UUID workflowId,
            UUID executionId,
            UUID anomalyId,
            String title,
            String description,
            AnomalySeverity severity,
            Map<String, Object> metadata) {
        return open(organizationId, workflowId, executionId, anomalyId, null, title, description, severity, metadata);
    }

    public static Incident open(
            UUID organizationId,
            UUID workflowId,
            UUID executionId,
            UUID anomalyId,
            String fingerprint,
            String title,
            String description,
            AnomalySeverity severity,
            Map<String, Object> metadata) {

        Incident incident = new Incident();

        incident.id = UUID.randomUUID();
        incident.organizationId = organizationId;
        incident.workflowId = workflowId;
        incident.executionId = executionId;
        incident.anomalyId = anomalyId;
        incident.fingerprint = fingerprint;
        incident.title = title;
        incident.description = description;
        incident.severity = severity != null ? severity : AnomalySeverity.MEDIUM;
        incident.status = IncidentStatus.OPEN;
        incident.metadata = metadata;

        Instant now = now();
        incident.createdAt = now;
        incident.updatedAt = now;

        return incident;
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    private void touch() {
        this.updatedAt = now();
    }

    public void recordRecurrence(
            UUID executionId,
            UUID anomalyId,
            AnomalySeverity newSeverity,
            String description,
            Map<String, Object> newMetadata) {
        if (executionId != null) {
            this.executionId = executionId;
        }
        if (anomalyId != null) {
            this.anomalyId = anomalyId;
        }
        if (newSeverity != null && newSeverity.ordinal() > this.severity.ordinal()) {
            this.severity = newSeverity;
        }
        if (description != null && !description.isBlank()) {
            this.description = description;
        }
        if (newMetadata != null) {
            this.metadata = newMetadata;
        }
        if (this.status == IncidentStatus.ACKNOWLEDGED) {
            this.status = IncidentStatus.OPEN;
        }
        touch();
    }

    public void reopen(
            UUID executionId,
            UUID anomalyId,
            AnomalySeverity severity,
            String description,
            Map<String, Object> newMetadata) {
        this.status = IncidentStatus.OPEN;
        this.resolvedAt = null;
        recordRecurrence(executionId, anomalyId, severity, description, newMetadata);
    }

    public void acknowledge() {
        if (this.status == IncidentStatus.OPEN) {
            this.status = IncidentStatus.ACKNOWLEDGED;
            this.acknowledgedAt = now();
            touch();
        }
    }

    public void resolve() {
        if (this.status != IncidentStatus.RESOLVED) {
            this.status = IncidentStatus.RESOLVED;
            this.resolvedAt = now();
            touch();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getWorkflowId() {
        return workflowId;
    }

    public UUID getExecutionId() {
        return executionId;
    }

    public UUID getAnomalyId() {
        return anomalyId;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public AnomalySeverity getSeverity() {
        return severity;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
