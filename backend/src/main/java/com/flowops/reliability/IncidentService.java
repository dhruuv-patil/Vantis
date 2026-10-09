package com.flowops.reliability;

import com.flowops.api.IncidentResponse;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.audit.AuditService;
import com.flowops.domain.AnomalySeverity;
import com.flowops.domain.AuditAction;
import com.flowops.domain.Incident;
import com.flowops.domain.IncidentStatus;
import com.flowops.repository.IncidentRepository;
import com.flowops.security.FlowOpsPrincipal;
import java.util.List;
import java.util.Map;
import com.flowops.config.ReliabilityProperties;
import com.flowops.domain.Anomaly;
import com.flowops.domain.AnomalyStatus;
import com.flowops.repository.AnomalyRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {

    private final IncidentRepository incidents;
    private final AuditService auditService;
    private final AnomalyRepository anomalyRepository;
    private final ReliabilityProperties properties;

    @Autowired
    public IncidentService(
            IncidentRepository incidents,
            @Autowired(required = false) AuditService auditService,
            @Autowired(required = false) AnomalyRepository anomalyRepository,
            @Autowired(required = false) ReliabilityProperties properties) {
        this.incidents = incidents;
        this.auditService = auditService;
        this.anomalyRepository = anomalyRepository;
        this.properties = properties;
    }

    public IncidentService(IncidentRepository incidents, AuditService auditService) {
        this(incidents, auditService, null, null);
    }

    public IncidentService(IncidentRepository incidents) {
        this(incidents, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<IncidentResponse> list(
            UUID organizationId,
            UUID workflowId,
            IncidentStatus status) {

        List<Incident> results;
        if (workflowId != null) {
            results = incidents.findByOrganizationIdAndWorkflowIdOrderByCreatedAtDesc(organizationId, workflowId);
        } else if (status != null) {
            results = incidents.findByOrganizationIdAndStatusOrderByCreatedAtDesc(organizationId, status);
        } else {
            results = incidents.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
        }

        return results.stream().map(IncidentResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public IncidentResponse get(UUID organizationId, UUID incidentId) {
        Incident incident = incidents.findByIdAndOrganizationId(incidentId, organizationId)
                .orElseThrow(() -> new ApiException(ErrorCode.INCIDENT_NOT_FOUND));
        return IncidentResponse.of(incident);
    }

    @Transactional
    public IncidentResponse create(
            UUID organizationId,
            UUID workflowId,
            UUID executionId,
            UUID anomalyId,
            String title,
            String description,
            AnomalySeverity severity,
            Map<String, Object> metadata) {
        return create(organizationId, workflowId, executionId, anomalyId, null, title, description, severity, metadata);
    }

    @Transactional
    public IncidentResponse create(
            UUID organizationId,
            UUID workflowId,
            UUID executionId,
            UUID anomalyId,
            String fingerprint,
            String title,
            String description,
            AnomalySeverity severity,
            Map<String, Object> metadata) {

        Incident incident = Incident.open(
                organizationId,
                workflowId,
                executionId,
                anomalyId,
                fingerprint,
                title,
                description,
                severity,
                metadata);

        return IncidentResponse.of(incidents.save(incident));
    }

    /**
     * Records or aggregates an anomaly incident by fingerprint, reopening on cooldown recurrence
     * and preventing duplicate cards for the same underlying failure.
     */
    @Transactional
    public Incident recordAnomalyIncident(
            UUID organizationId,
            UUID workflowId,
            UUID executionId,
            UUID anomalyId,
            String fingerprint,
            String title,
            String description,
            AnomalySeverity severity,
            Map<String, Object> evidence) {

        // 1. Look for existing open or acknowledged incident with this fingerprint
        Optional<Incident> openOpt = incidents.findFirstByOrganizationIdAndFingerprintAndStatusInOrderByCreatedAtDesc(
                organizationId, fingerprint, List.of(IncidentStatus.OPEN, IncidentStatus.ACKNOWLEDGED));

        if (openOpt.isPresent()) {
            Incident existing = openOpt.get();
            Map<String, Object> meta = new LinkedHashMap<>();
            if (existing.getMetadata() != null) {
                meta.putAll(existing.getMetadata());
            }
            int affected = meta.get("affectedExecutions") instanceof Number n ? n.intValue() + 1 : 2;
            meta.put("affectedExecutions", affected);
            meta.put("fingerprint", fingerprint);
            if (evidence != null) {
                meta.put("lastEvidence", evidence);
            }

            existing.recordRecurrence(executionId, anomalyId, severity, description, meta);
            return incidents.save(existing);
        }

        // 2. Look for recently resolved incident within cooldown window
        Optional<Incident> latestOpt = incidents.findFirstByOrganizationIdAndFingerprintOrderByCreatedAtDesc(
                organizationId, fingerprint);

        Duration cooldown = properties != null ? properties.cooldown() : Duration.ofMinutes(15);
        if (latestOpt.isPresent() && latestOpt.get().getStatus() == IncidentStatus.RESOLVED) {
            Incident resolved = latestOpt.get();
            Instant resolvedAt = resolved.getResolvedAt() != null ? resolved.getResolvedAt() : resolved.getUpdatedAt();
            if (resolvedAt != null && Duration.between(resolvedAt, Instant.now()).compareTo(cooldown) < 0) {
                // Premature resolution recurrence! Re-open existing incident
                Map<String, Object> meta = new LinkedHashMap<>();
                if (resolved.getMetadata() != null) {
                    meta.putAll(resolved.getMetadata());
                }
                int affected = meta.get("affectedExecutions") instanceof Number n ? n.intValue() + 1 : 1;
                meta.put("affectedExecutions", affected);
                meta.put("reopenedAt", Instant.now().toString());
                if (evidence != null) {
                    meta.put("lastEvidence", evidence);
                }

                resolved.reopen(executionId, anomalyId, severity, description, meta);
                Incident saved = incidents.save(resolved);
                if (auditService != null) {
                    auditService.record(
                            organizationId,
                            null,
                            null,
                            AuditAction.INCIDENT_ACKNOWLEDGED,
                            saved.getId().toString(),
                            "Incident re-opened due to recurrence during cooldown: " + saved.getTitle());
                }
                return saved;
            }
        }

        // 3. Open a brand-new incident
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("fingerprint", fingerprint);
        meta.put("affectedExecutions", 1);
        if (evidence != null) {
            meta.put("evidence", evidence);
        }

        Incident newIncident = Incident.open(
                organizationId,
                workflowId,
                executionId,
                anomalyId,
                fingerprint,
                title,
                description,
                severity,
                meta);

        return incidents.save(newIncident);
    }

    @Transactional
    public void resolveLinkedIncident(UUID organizationId, UUID anomalyId) {
        List<Incident> linked = incidents.findByOrganizationIdAndAnomalyId(organizationId, anomalyId);
        for (Incident inc : linked) {
            if (inc.getStatus() != IncidentStatus.RESOLVED) {
                inc.resolve();
                incidents.save(inc);
                if (auditService != null) {
                    auditService.record(
                            organizationId,
                            null,
                            null,
                            AuditAction.INCIDENT_RESOLVED,
                            inc.getId().toString(),
                            "Incident resolved via recovery verification: " + inc.getTitle());
                }
            }
        }
    }

    @Transactional
    public IncidentResponse acknowledge(UUID organizationId, UUID incidentId) {
        Incident incident = incidents.findByIdAndOrganizationId(incidentId, organizationId)
                .orElseThrow(() -> new ApiException(ErrorCode.INCIDENT_NOT_FOUND));
        incident.acknowledge();
        Incident saved = incidents.save(incident);
        if (auditService != null) {
            auditService.record(
                    organizationId,
                    null,
                    null,
                    AuditAction.INCIDENT_ACKNOWLEDGED,
                    incidentId.toString(),
                    "Incident acknowledged: " + incident.getTitle());
        }
        return IncidentResponse.of(saved);
    }

    @Transactional
    public IncidentResponse acknowledge(FlowOpsPrincipal principal, UUID incidentId) {
        Incident incident = incidents.findByIdAndOrganizationId(incidentId, principal.organizationId())
                .orElseThrow(() -> new ApiException(ErrorCode.INCIDENT_NOT_FOUND));
        incident.acknowledge();
        Incident saved = incidents.save(incident);
        if (auditService != null) {
            auditService.record(
                    principal,
                    AuditAction.INCIDENT_ACKNOWLEDGED,
                    incidentId.toString(),
                    "Incident acknowledged: " + incident.getTitle());
        }
        return IncidentResponse.of(saved);
    }

    @Transactional
    public IncidentResponse resolve(UUID organizationId, UUID incidentId) {
        Incident incident = incidents.findByIdAndOrganizationId(incidentId, organizationId)
                .orElseThrow(() -> new ApiException(ErrorCode.INCIDENT_NOT_FOUND));

        verifyRecoveryBeforeResolve(organizationId, incident);

        incident.resolve();
        Incident saved = incidents.save(incident);
        if (auditService != null) {
            auditService.record(
                    organizationId,
                    null,
                    null,
                    AuditAction.INCIDENT_RESOLVED,
                    incidentId.toString(),
                    "Incident resolved: " + incident.getTitle());
        }
        return IncidentResponse.of(saved);
    }

    @Transactional
    public IncidentResponse resolve(FlowOpsPrincipal principal, UUID incidentId) {
        Incident incident = incidents.findByIdAndOrganizationId(incidentId, principal.organizationId())
                .orElseThrow(() -> new ApiException(ErrorCode.INCIDENT_NOT_FOUND));

        verifyRecoveryBeforeResolve(principal.organizationId(), incident);

        incident.resolve();
        Incident saved = incidents.save(incident);
        if (auditService != null) {
            auditService.record(
                    principal,
                    AuditAction.INCIDENT_RESOLVED,
                    incidentId.toString(),
                    "Incident resolved: " + incident.getTitle());
        }
        return IncidentResponse.of(saved);
    }

    private void verifyRecoveryBeforeResolve(UUID organizationId, Incident incident) {
        if (incident.getAnomalyId() != null && anomalyRepository != null) {
            Optional<Anomaly> anomalyOpt = anomalyRepository.findByIdAndOrganizationId(incident.getAnomalyId(), organizationId);
            if (anomalyOpt.isPresent()) {
                Anomaly anomaly = anomalyOpt.get();
                if (anomaly.getStatus() != AnomalyStatus.RESOLVED && anomaly.getStatus() != AnomalyStatus.FALSE_POSITIVE) {
                    throw new ApiException(
                            ErrorCode.ANOMALY_ALREADY_CLOSED,
                            "Incident cannot be marked resolved until recovery is verified by healthy executions against baseline (anomaly status is " + anomaly.getStatus() + ")");
                }
            }
        }
    }
}
