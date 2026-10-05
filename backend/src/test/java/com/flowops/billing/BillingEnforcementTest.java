package com.flowops.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.ai.AiController;
import com.flowops.ai.AiServiceClient;
import com.flowops.ai.GenerateWorkflowRequest;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.common.ratelimit.RateLimiter;
import com.flowops.config.ExecutionProperties;
import com.flowops.domain.Role;
import com.flowops.domain.Workflow;
import com.flowops.domain.WorkflowVersion;
import com.flowops.execution.ExecutionService;
import com.flowops.integration.delivery.WebhookEventDispatcher;
import com.flowops.repository.WorkflowExecutionRepository;
import com.flowops.repository.WorkflowRepository;
import com.flowops.repository.WorkflowVersionRepository;
import com.flowops.security.FlowOpsPrincipal;
import com.flowops.workflow.CreateWorkflowRequest;
import com.flowops.workflow.WorkflowService;
import com.flowops.workflow.nodes.NodeRegistry;
import com.flowops.workflow.validation.WorkflowValidator;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class BillingEnforcementTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID MEMBERSHIP_ID = UUID.randomUUID();
    private static final FlowOpsPrincipal PRINCIPAL =
            new FlowOpsPrincipal(USER_ID, MEMBERSHIP_ID, ORG_ID, Role.MEMBER, "member@vantis.ai");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private BillingService billingService;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowVersionRepository versionRepository;

    @Mock
    private WorkflowExecutionRepository executionRepository;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(PRINCIPAL, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("ExecutionService blocks execution creation when billing quota is reached")
    void executionServiceBlocksWhenQuotaReached() {
        UUID workflowId = UUID.randomUUID();
        ObjectNode emptyGraph = MAPPER.createObjectNode();
        Workflow workflow = Workflow.create(ORG_ID, USER_ID, "Test", "Desc", emptyGraph);
        workflow.markPublished(1);

        WorkflowVersion version = WorkflowVersion.create(
                workflow.getId(), 1, emptyGraph, "initial", USER_ID);

        when(workflowRepository.findByIdAndOrganizationId(workflowId, ORG_ID))
                .thenReturn(Optional.of(workflow));
        when(versionRepository.findByWorkflowIdAndVersionNumber(workflow.getId(), 1))
                .thenReturn(Optional.of(version));

        doThrow(new ApiException(ErrorCode.PLAN_LIMIT_EXCEEDED, "Execution limit reached"))
                .when(billingService).assertCanExecute(ORG_ID);

        ExecutionService executionService = new ExecutionService(
                workflowRepository,
                versionRepository,
                executionRepository,
                mock(com.flowops.repository.ExecutionNodeRepository.class),
                mock(com.flowops.repository.ExecutionLogRepository.class),
                mock(com.flowops.execution.QueueService.class),
                mock(com.flowops.execution.ExecutionEvents.class),
                new ExecutionProperties(4, 3, Duration.ofSeconds(2), Duration.ofSeconds(30), 300, Duration.ofSeconds(5), Duration.ofSeconds(30), 1048576, Duration.ofMinutes(30)),
                new NodeRegistry(),
                MAPPER,
                mock(com.flowops.repository.IdempotencyKeyRepository.class),
                billingService);

        assertThatThrownBy(() -> executionService.run(PRINCIPAL, workflowId, null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED));

        verify(executionRepository, never()).save(any());
    }

    @Test
    @DisplayName("WorkflowService blocks workflow creation when plan limit is reached")
    void workflowServiceBlocksWhenQuotaReached() {
        doThrow(new ApiException(ErrorCode.PLAN_LIMIT_EXCEEDED, "Active workflow limit reached"))
                .when(billingService).assertCanCreateWorkflow(ORG_ID);

        WorkflowService workflowService = new WorkflowService(
                workflowRepository,
                versionRepository,
                mock(WorkflowValidator.class),
                MAPPER,
                mock(WebhookEventDispatcher.class),
                billingService);

        assertThatThrownBy(() -> workflowService.create(PRINCIPAL, new CreateWorkflowRequest("New WF", "Description")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED));

        verify(workflowRepository, never()).save(any());
    }

    @Test
    @DisplayName("AiController blocks AI generation when quota reached")
    void aiControllerBlocksWhenQuotaReached() {
        AiServiceClient aiService = mock(AiServiceClient.class);
        RateLimiter rateLimiter = mock(RateLimiter.class);

        doThrow(new ApiException(ErrorCode.PLAN_LIMIT_EXCEEDED, "AI limit reached"))
                .when(billingService).assertCanGenerateAi(ORG_ID);

        AiController controller = new AiController(
                aiService,
                new NodeRegistry(),
                MAPPER,
                rateLimiter,
                billingService);

        assertThatThrownBy(() -> controller.generate(new GenerateWorkflowRequest("Create invoice parser")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED));

        verify(aiService, never()).generateWorkflow(any());
        verify(billingService, never()).recordAiUsage(any(), any(), any(int.class), any());
    }

    @Test
    @DisplayName("AiController records usage only upon successful generation")
    void aiControllerRecordsUsageOnSuccess() {
        AiServiceClient aiService = mock(AiServiceClient.class);
        RateLimiter rateLimiter = mock(RateLimiter.class);

        ObjectNode rawGraph = MAPPER.createObjectNode();
        ArrayNode nodes = rawGraph.putArray("nodes");
        ObjectNode node1 = nodes.addObject();
        node1.put("id", "trigger_1");
        node1.put("type", "manual_trigger");
        rawGraph.putArray("edges");

        AiServiceClient.Generation successfulGen = new AiServiceClient.Generation(true, rawGraph, "Generated graph");
        when(aiService.generateWorkflow("Build ETL flow")).thenReturn(successfulGen);

        AiController controller = new AiController(
                aiService,
                new NodeRegistry(),
                MAPPER,
                rateLimiter,
                billingService);

        controller.generate(new GenerateWorkflowRequest("Build ETL flow"));

        verify(billingService).assertCanGenerateAi(ORG_ID);
        verify(billingService).recordAiUsage(
                eq(ORG_ID),
                eq("AI_WORKFLOW_GENERATION"),
                eq(1),
                eq("Build ETL flow"));
    }
}
