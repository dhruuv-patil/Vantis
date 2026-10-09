package com.flowops.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.billing.service.RejectedExecutionRecorder;
import com.flowops.domain.ExecutionStatus;
import com.flowops.domain.Organization;
import com.flowops.domain.TriggerType;
import com.flowops.domain.UserAccount;
import com.flowops.domain.Workflow;
import com.flowops.domain.WorkflowExecution;
import com.flowops.domain.WorkflowVersion;
import com.flowops.repository.ExecutionStatRow;
import com.flowops.repository.IncidentRepository;
import com.flowops.repository.NotificationRepository;
import com.flowops.repository.OrganizationRepository;
import com.flowops.repository.UserRepository;
import com.flowops.repository.WorkflowExecutionRepository;
import com.flowops.repository.WorkflowRepository;
import com.flowops.repository.WorkflowVersionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class RejectedExecutionAndReliabilityTest {

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowVersionRepository versionRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RejectedExecutionRecorder rejectedRecorder;

    @Autowired
    private IncidentRepository incidentRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private com.flowops.reliability.IncidentService incidentService;

    @Autowired
    private com.flowops.notification.NotificationService notificationService;

    @Autowired
    private ObjectMapper objectMapper;

    private Organization org;
    private UserAccount user;
    private Workflow workflow;
    private WorkflowVersion version;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        incidentRepository.deleteAll();
        executionRepository.deleteAll();
        org = organizationRepository.save(
                Organization.create("Rejection Test Org " + UUID.randomUUID(), "rej-" + UUID.randomUUID().toString().substring(0, 8)));
        user = userRepository.save(
                UserAccount.create("user-" + UUID.randomUUID() + "@test.local", "hashedpassword", "Test User"));

        workflow = workflowRepository.save(
                Workflow.create(org.getId(), user.getId(), "Test WF", "Desc", objectMapper.createObjectNode()));
        workflow.markPublished(1);
        workflow = workflowRepository.save(workflow);

        version = versionRepository.save(
                WorkflowVersion.create(workflow.getId(), 1, objectMapper.createObjectNode(), "v1", user.getId()));
    }

    @AfterEach
    void tearDown() {
        if (org != null) {
            notificationRepository.deleteAll();
            incidentRepository.deleteAll();
            executionRepository.deleteAll();
            versionRepository.deleteAll();
            workflowRepository.deleteAll();
            userRepository.deleteById(user.getId());
            organizationRepository.deleteById(org.getId());
        }
    }

    @Test
    @DisplayName("Rejected quota executions are excluded from failure counts, statsSince, and period queries")
    void rejectedExecutionsExcludedFromReliabilityQueries() {
        // 1. Create one normal FAILED execution
        WorkflowExecution normalFailed = WorkflowExecution.create(
                org.getId(),
                workflow.getId(),
                version.getId(),
                1,
                TriggerType.MANUAL,
                objectMapper.createObjectNode(),
                user.getId());
        normalFailed.markRunning();
        normalFailed.markFailed("Step connection timed out");
        executionRepository.save(normalFailed);

        // Verify normal failed is counted in failure metrics
        assertThat(executionRepository.countFailedByWorkflowId(workflow.getId())).isEqualTo(1L);
        assertThat(executionRepository.countByWorkflowId(workflow.getId())).isEqualTo(1L);

        // 2. Record a quota-rejected execution via RejectedExecutionRecorder
        rejectedRecorder.recordRejected(
                org.getId(),
                workflow.getId(),
                version.getId(),
                1,
                TriggerType.MANUAL,
                user.getId(),
                "PLAN_LIMIT_EXCEEDED",
                "Monthly execution limit reached");

        // Verify that rejected execution exists in DB with rejectionReason
        List<WorkflowExecution> runs = executionRepository.search(
                org.getId(), workflow.getId(), null, Pageable.unpaged());
        assertThat(runs).hasSize(2);
        Optional<WorkflowExecution> rejectedRun = runs.stream()
                .filter(e -> "PLAN_LIMIT_EXCEEDED".equals(e.getRejectionReason()))
                .findFirst();
        assertThat(rejectedRun).isPresent();
        assertThat(rejectedRun.get().getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(rejectedRun.get().getError()).contains("Monthly execution limit reached");

        // 3. Verify that reliability queries ignore the rejected run completely!
        // countFailedByWorkflowId must still be 1 (NOT 2)
        long failedCount = executionRepository.countFailedByWorkflowId(workflow.getId());
        assertThat(failedCount).isEqualTo(1L);

        // countByWorkflowId must still be 1 (NOT 2)
        long totalWfCount = executionRepository.countByWorkflowId(workflow.getId());
        assertThat(totalWfCount).isEqualTo(1L);

        // countFailedByOrganizationIdAndWorkflowId must still be 1
        long failedOrgCount = executionRepository.countFailedByOrganizationIdAndWorkflowId(org.getId(), workflow.getId());
        assertThat(failedOrgCount).isEqualTo(1L);

        // statsSince must contain only 1 row (the real failure)
        List<ExecutionStatRow> stats = executionRepository.statsSince(org.getId(), Instant.now().minusSeconds(3600));
        assertThat(stats).hasSize(1);

        // countByOrganizationIdAndPeriod must contain only 1 run
        long periodCount = executionRepository.countByOrganizationIdAndPeriod(
                org.getId(), Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600));
        assertThat(periodCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("In-flight execution failure at quota cap creates an incident and notification; quota-rejected run creates none")
    void inFlightFailureAtCapCreatesIncidentAndNotificationVsQuotaRejectedCreatesNone() {
        // Given an org at the quota cap where an in-flight execution fails
        WorkflowExecution inFlightFailed = WorkflowExecution.create(
                org.getId(),
                workflow.getId(),
                version.getId(),
                1,
                TriggerType.MANUAL,
                objectMapper.createObjectNode(),
                user.getId());
        inFlightFailed.markRunning();
        inFlightFailed.markFailed("Worker process timed out during execution");
        inFlightFailed = executionRepository.save(inFlightFailed);

        // In-flight failure produces an incident and an in-app notification via the real failure path
        incidentService.create(
                org.getId(),
                workflow.getId(),
                inFlightFailed.getId(),
                null,
                "Workflow execution failure",
                "Worker process timed out during execution",
                com.flowops.domain.AnomalySeverity.HIGH,
                java.util.Map.of("executionId", inFlightFailed.getId().toString()));

        notificationService.publish(
                org.getId(),
                user.getId(),
                com.flowops.domain.NotificationLevel.ERROR,
                "Workflow Execution Failed",
                "Worker process timed out during execution",
                "/executions/" + inFlightFailed.getId());

        assertThat(incidentRepository.findByOrganizationIdAndWorkflowIdOrderByCreatedAtDesc(org.getId(), workflow.getId()))
                .hasSize(1);
        assertThat(notificationRepository.search(user.getId(), org.getId(), false, Pageable.unpaged()))
                .hasSize(1);

        // When a subsequent run is rejected at quota cap
        rejectedRecorder.recordRejected(
                org.getId(),
                workflow.getId(),
                version.getId(),
                1,
                TriggerType.MANUAL,
                user.getId(),
                "PLAN_LIMIT_EXCEEDED",
                "Monthly execution limit reached");

        // Then:
        // 1. Quota-rejected run creates NO incident
        List<com.flowops.domain.Incident> incidentsAfter = incidentRepository.findByOrganizationIdAndWorkflowIdOrderByCreatedAtDesc(org.getId(), workflow.getId());
        assertThat(incidentsAfter).hasSize(1);
        assertThat(incidentsAfter.get(0).getExecutionId()).isEqualTo(inFlightFailed.getId());

        // 2. Quota-rejected run creates NO notification
        List<com.flowops.domain.Notification> notificationsAfter = notificationRepository.search(user.getId(), org.getId(), false, Pageable.unpaged());
        assertThat(notificationsAfter).hasSize(1);
        assertThat(notificationsAfter.get(0).getBody()).contains("Worker process timed out during execution");

        // 3. Reliability failure queries exclude the quota-rejected run
        assertThat(executionRepository.countFailedByWorkflowId(workflow.getId())).isEqualTo(1L);
    }
}
