package com.flowops.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A workflow definition owned by exactly one organization.
 *
 * <p>Holds the editable working graph in {@code draftGraph}; each publish freezes
 * a copy into an immutable {@link WorkflowVersion}. Foreign keys are plain UUIDs
 * (contract §2 rule 6) so every query stays explicitly org-scoped.
 */
@Entity
@Table(name = "workflows")
public class Workflow {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private WorkflowStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft_graph", nullable = false)
    private JsonNode draftGraph;

    @Column(name = "latest_version")
    private Integer latestVersion;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Workflow() {
        // JPA
    }

    public static Workflow create(
            UUID organizationId, UUID createdBy, String name, String description, JsonNode emptyGraph) {
        Workflow workflow = new Workflow();
        workflow.id = UUID.randomUUID();
        workflow.organizationId = organizationId;
        workflow.createdBy = createdBy;
        workflow.name = name;
        workflow.description = description;
        workflow.status = WorkflowStatus.DRAFT;
        workflow.draftGraph = emptyGraph;
        workflow.latestVersion = null;
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        workflow.createdAt = now;
        workflow.updatedAt = now;
        return workflow;
    }

    private void touch() {
        this.updatedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /** Updates the mutable metadata. Nulls are treated as "leave unchanged" upstream. */
    public void editMetadata(String name, String description) {
        this.name = name;
        this.description = description;
        touch();
    }

    /** Replaces the working graph. Does not change status or create a version. */
    public void saveDraft(JsonNode graph) {
        this.draftGraph = graph;
        touch();
    }

    /** Records that a new immutable version was published from the current draft. */
    public void markPublished(int versionNumber) {
        this.status = WorkflowStatus.PUBLISHED;
        this.latestVersion = versionNumber;
        touch();
    }

    public void archive() {
        this.status = WorkflowStatus.ARCHIVED;
        touch();
    }

    /**
     * Deactivates a published workflow due to plan downgrade or limit enforcement.
     * Preserves version history, definitions, and draft graph.
     */
    public void deactivate() {
        this.status = WorkflowStatus.DRAFT;
        touch();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public WorkflowStatus getStatus() {
        return status;
    }

    public JsonNode getDraftGraph() {
        return draftGraph;
    }

    public Integer getLatestVersion() {
        return latestVersion;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
