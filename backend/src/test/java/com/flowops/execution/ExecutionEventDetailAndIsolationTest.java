package com.flowops.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.api.ExecutionEventEnvelopes;
import com.flowops.api.IncidentResponse;
import com.flowops.api.IntegrationResponse;
import com.flowops.audit.AuditService;
import com.flowops.common.crypto.CredentialCipher;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.AnomalySeverity;
import com.flowops.domain.AuditAction;
import com.flowops.domain.AuditLog;
import com.flowops.domain.ExecutionEvent;
import com.flowops.domain.Incident;
import com.flowops.domain.IncidentStatus;
import com.flowops.domain.Integration;
import com.flowops.domain.Organization;
import com.flowops.domain.Role;
import com.flowops.domain.UserAccount;
import com.flowops.domain.Workflow;
import com.flowops.integration.IntegrationService;
import com.flowops.reliability.IncidentService;
import com.flowops.repository.AuditLogRepository;
import com.flowops.repository.ExecutionEventRepository;
import com.flowops.repository.IncidentRepository;
import com.flowops.repository.IntegrationRepository;
import com.flowops.repository.OrganizationRepository;
import com.flowops.repository.UserRepository;
import com.flowops.repository.WorkflowRepository;
import com.flowops.security.FlowOpsPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
class ExecutionEventDetailAndIsolationTest {

    @Autowired
    private ExecutionEventRepository eventRepository;

    @Autowired
    private ExecutionEventService eventService;

    @Autowired
    private ExecutionEventController eventController;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private IncidentService incidentService;

    @Autowired
    private IntegrationRepository integrationRepository;

    @Autowired
    private IntegrationService integrationService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private CredentialCipher cipher;

    @Autowired
    private ObjectMapper objectMapper;

    private Organization orgA;
    private Organization orgB;
    private UserAccount userA;
    private UserAccount userB;
    private Workflow workflowA;
    private Workflow workflowB;

    private FlowOpsPrincipal principalA;
    private FlowOpsPrincipal principalB;

    @BeforeEach
    void setUp() {
        auditLogRepository.deleteAll();
        eventRepository.deleteAll();
        incidentRepository.deleteAll();

        orgA = organizationRepository.save(Organization.create("Org A " + UUID.randomUUID(), "org-a-" + UUID.randomUUID().toString().substring(0, 8)));
        orgB = organizationRepository.save(Organization.create("Org B " + UUID.randomUUID(), "org-b-" + UUID.randomUUID().toString().substring(0, 8)));

        userA = userRepository.save(UserAccount.create("user-a-" + UUID.randomUUID() + "@test.local", "hash", "User A"));
        userB = userRepository.save(UserAccount.create("user-b-" + UUID.randomUUID() + "@test.local", "hash", "User B"));

        workflowA = workflowRepository.save(Workflow.create(orgA.getId(), userA.getId(), "WF A", "Desc A", objectMapper.createObjectNode()));
        workflowB = workflowRepository.save(Workflow.create(orgB.getId(), userB.getId(), "WF B", "Desc B", objectMapper.createObjectNode()));

        principalA = new FlowOpsPrincipal(userA.getId(), UUID.randomUUID(), orgA.getId(), Role.OWNER, userA.getEmail());
        principalB = new FlowOpsPrincipal(userB.getId(), UUID.randomUUID(), orgB.getId(), Role.OWNER, userB.getEmail());
    }

    @AfterEach
    void tearDown() {
        auditLogRepository.deleteAll();
        eventRepository.deleteAll();
        incidentRepository.deleteAll();
        if (workflowA != null) workflowRepository.deleteById(workflowA.getId());
        if (workflowB != null) workflowRepository.deleteById(workflowB.getId());
        if (userA != null) userRepository.deleteById(userA.getId());
        if (userB != null) userRepository.deleteById(userB.getId());
        if (orgA != null) organizationRepository.deleteById(orgA.getId());
        if (orgB != null) organizationRepository.deleteById(orgB.getId());
    }

    @Test
    void executionDetailRetrievalFindsEventBeyondDefaultLimit() {
        // Create 60 execution events for orgA.
        // The first one has executionExternalId = "target-exec-id"
        ExecutionEvent targetEvent = ExecutionEvent.create(
                orgA.getId(), workflowA.getId(), "n8n", "wf-ext-1", "target-exec-id",
                "step-1", "HTTP Request", "SUCCESS", Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(119), 1000L, 0, 10, 20, null, null, Map.of(), null);
        eventRepository.save(targetEvent);

        for (int i = 1; i <= 60; i++) {
            ExecutionEvent filler = ExecutionEvent.create(
                    orgA.getId(), workflowA.getId(), "n8n", "wf-ext-1", "filler-exec-" + i,
                    "step-1", "HTTP Request", "SUCCESS", Instant.now().minusSeconds(100 - i),
                    Instant.now().minusSeconds(99 - i), 1000L, 0, 10, 20, null, null, Map.of(), null);
            eventRepository.save(filler);
        }

        // listEvents without filter returns limit (50 events), which would prune target-exec-id in the old bug
        var listDefault = eventService.listEvents(principalA, null, 50);
        assertThat(listDefault).hasSize(50);
        assertThat(listDefault.stream().anyMatch(e -> "target-exec-id".equals(e.getExecutionExternalId()))).isFalse();

        // Querying specifically with executionExternalId returns the target event!
        var foundEvents = eventService.findExternalExecution(principalA, "target-exec-id", null);
        assertThat(foundEvents).hasSize(1);
        assertThat(foundEvents.get(0).getExecutionExternalId()).isEqualTo("target-exec-id");

        // Controller method with executionExternalId also returns it
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principalA, null, List.of()));
        try {
            var controllerResponse = eventController.listEvents(null, "target-exec-id", null, 50);
            assertThat(controllerResponse.events()).hasSize(1);
            assertThat(controllerResponse.events().get(0).executionExternalId()).isEqualTo("target-exec-id");

            // Controller dedicated endpoint also returns it
            var dedicatedResponse = eventController.getExternalExecution("target-exec-id", null);
            assertThat(dedicatedResponse.events()).hasSize(1);
            assertThat(dedicatedResponse.events().get(0).executionExternalId()).isEqualTo("target-exec-id");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void tenantIsolationBlocksCrossTenantExecutionReads() {
        ExecutionEvent eventA = ExecutionEvent.create(
                orgA.getId(), workflowA.getId(), "n8n", "wf-ext-1", "exec-org-a",
                "step-1", "HTTP Request", "SUCCESS", Instant.now().minusSeconds(10),
                Instant.now(), 1000L, 0, 10, 20, null, null, Map.of(), null);
        eventRepository.save(eventA);

        // Org B queries Org A's execution external ID -> must return empty list (no leakage)
        var eventsForB = eventService.findExternalExecution(principalB, "exec-org-a", null);
        assertThat(eventsForB).isEmpty();

        // Org B querying event by UUID -> throws 404 NOT_FOUND
        assertThatThrownBy(() -> eventService.getEvent(principalB, eventA.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test
    void incidentTenantIsolationAndAuditLogging() {
        Incident incident = Incident.open(
                orgA.getId(), workflowA.getId(), null, null,
                "Outage detected in Org A", "External failure",
                AnomalySeverity.CRITICAL, Map.of());
        incidentRepository.save(incident);

        // Org B querying Org A incident -> throws NOT_FOUND
        assertThatThrownBy(() -> incidentService.get(orgB.getId(), incident.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INCIDENT_NOT_FOUND));

        // Org B attempting to acknowledge Org A incident -> throws NOT_FOUND
        assertThatThrownBy(() -> incidentService.acknowledge(principalB, incident.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INCIDENT_NOT_FOUND));

        // Org A acknowledges incident
        IncidentResponse ackResponse = incidentService.acknowledge(principalA, incident.getId());
        assertThat(ackResponse.status()).isEqualTo(IncidentStatus.ACKNOWLEDGED);

        // Verify audit log recorded for ACKNOWLEDGE
        List<AuditLog> auditLogsAck = auditLogRepository.search(orgA.getId(), null, org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(auditLogsAck.stream().anyMatch(l -> l.getAction() == AuditAction.INCIDENT_ACKNOWLEDGED)).isTrue();

        // Org A resolves incident
        IncidentResponse resResponse = incidentService.resolve(principalA, incident.getId());
        assertThat(resResponse.status()).isEqualTo(IncidentStatus.RESOLVED);

        // Verify audit log recorded for RESOLVE
        List<AuditLog> auditLogsRes = auditLogRepository.search(orgA.getId(), null, org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(auditLogsRes.stream().anyMatch(l -> l.getAction() == AuditAction.INCIDENT_RESOLVED)).isTrue();
    }

    @Test
    void integrationCredentialRotationAndAuditLogging() {
        Integration integration = Integration.createConnected(
                orgA.getId(), userA.getId(), "n8n", "n8n Production", "4567");
        integrationRepository.save(integration);

        // Org B trying to update Org A's credentials -> throws INTEGRATION_NOT_FOUND
        assertThatThrownBy(() -> integrationService.updateCredentials(principalB, integration.getId(), Map.of("apiKey", "secret-token-b")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INTEGRATION_NOT_FOUND));

        // Org A rotates credentials
        IntegrationResponse updated = integrationService.updateCredentials(
                principalA, integration.getId(), Map.of("apiKey", "new-rotated-secret-key"));
        assertThat(updated.id()).isEqualTo(integration.getId());

        // Verify credentials can be decrypted
        var decrypted = integrationService.getDecryptedCredentials(principalA, integration.getId());
        assertThat(decrypted.require("apiKey")).isEqualTo("new-rotated-secret-key");

        // Verify audit log recorded
        List<AuditLog> logs = auditLogRepository.search(orgA.getId(), null, org.springframework.data.domain.PageRequest.of(0, 10));
        assertThat(logs.stream().anyMatch(l -> l.getAction() == AuditAction.INTEGRATION_CREDENTIAL_UPDATED)).isTrue();
    }
}
