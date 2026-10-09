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
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {

    private final IncidentRepository incidents;
    private final AuditService auditService;

    @org.springframework.beans.factory.annotation.Autowired
    public IncidentService(IncidentRepository incidents, @org.springframework.beans.factory.annotation.Autowired(required = false) AuditService auditService) {
        this.incidents = incidents;
        this.auditService = auditService;
    }

    public IncidentService(IncidentRepository incidents) {
        this(incidents, null);
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

        Incident incident = Incident.open(
                organizationId,
                workflowId,
                executionId,
                anomalyId,
                title,
                description,
                severity,
                metadata);

        return IncidentResponse.of(incidents.save(incident));
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
}
