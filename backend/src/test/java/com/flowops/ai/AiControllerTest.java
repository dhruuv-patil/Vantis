package com.flowops.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.api.GenerateWorkflowResponse;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.common.ratelimit.RateLimiter;
import com.flowops.domain.Role;
import com.flowops.security.FlowOpsPrincipal;
import com.flowops.workflow.nodes.NodeRegistry;
import com.flowops.workflow.validation.WorkflowValidator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class AiControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private AiServiceClient aiService;
    private NodeRegistry nodeRegistry;
    private RateLimiter rateLimiter;
    private WorkflowValidator validator;
    private AiController controller;

    @BeforeEach
    void setUp() {
        aiService = mock(AiServiceClient.class);
        nodeRegistry = new NodeRegistry();
        rateLimiter = mock(RateLimiter.class);
        validator = new WorkflowValidator(nodeRegistry, MAPPER);
        controller = new AiController(aiService, nodeRegistry, MAPPER, rateLimiter, null, validator);

        // Setup authenticated principal
        FlowOpsPrincipal principal = new FlowOpsPrincipal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Role.MEMBER,
                "test@vantis.run");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validGeneratedWorkflowPassesValidationAndReturnsResponse() throws Exception {
        String json = """
        {
          "nodes": [
            {
              "id": "trigger",
              "type": "manual_trigger",
              "label": "Manual Trigger",
              "config": {}
            },
            {
              "id": "get_customer_details",
              "type": "http_request",
              "label": "Get Customer Details",
              "config": {
                "method": "GET",
                "url": "https://api.example.com/customers/123"
              }
            },
            {
              "id": "send_confirmation_email",
              "type": "email",
              "label": "Send Confirmation Email",
              "config": {
                "to": "customer@example.com",
                "subject": "Welcome!",
                "body": "Welcome to Vantis"
              }
            }
          ],
          "edges": [
            {
              "source": "trigger",
              "target": "get_customer_details",
              "source_handle": null
            },
            {
              "source": "get_customer_details",
              "target": "send_confirmation_email",
              "source_handle": null
            }
          ]
        }
        """;
        JsonNode graphNode = MAPPER.readTree(json);
        when(aiService.generateWorkflow(anyString()))
                .thenReturn(new AiServiceClient.Generation(true, graphNode, "Test summary"));

        GenerateWorkflowRequest req = new GenerateWorkflowRequest("Create a workflow to fetch customer and send email");
        GenerateWorkflowResponse resp = controller.generate(req);

        assertThat(resp).isNotNull();
        assertThat(resp.notes()).isEqualTo("Test summary");
        assertThat(resp.graph().get("nodes")).hasSize(3);
        assertThat(resp.graph().get("edges")).hasSize(2);
    }

    @Test
    void unconfiguredAiThrowsAiNotConfigured() {
        when(aiService.generateWorkflow(anyString()))
                .thenReturn(new AiServiceClient.Generation(false, null, null));

        GenerateWorkflowRequest req = new GenerateWorkflowRequest("Any prompt");
        ApiException ex = assertThrows(ApiException.class, () -> controller.generate(req));
        assertThat(ex.code()).isEqualTo(ErrorCode.AI_NOT_CONFIGURED);
    }

    @Test
    void invalidGraphWithUnknownNodeFailsValidationWithAiGenerationFailed() throws Exception {
        String json = """
        {
          "nodes": [
            {
              "id": "unknown_1",
              "type": "non_existent_node_type",
              "config": {}
            }
          ],
          "edges": []
        }
        """;
        JsonNode graphNode = MAPPER.readTree(json);
        when(aiService.generateWorkflow(anyString()))
                .thenReturn(new AiServiceClient.Generation(true, graphNode, "Invalid"));

        GenerateWorkflowRequest req = new GenerateWorkflowRequest("Build an unknown node");
        ApiException ex = assertThrows(ApiException.class, () -> controller.generate(req));
        assertThat(ex.code()).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
    }

    @Test
    void nullGraphThrowsAiGenerationFailed() {
        when(aiService.generateWorkflow(anyString()))
                .thenReturn(new AiServiceClient.Generation(true, null, null));

        GenerateWorkflowRequest req = new GenerateWorkflowRequest("Build something");
        ApiException ex = assertThrows(ApiException.class, () -> controller.generate(req));
        assertThat(ex.code()).isEqualTo(ErrorCode.AI_GENERATION_FAILED);
    }

    @Test
    void unconfiguredAiRefundsAiGenerationTicket() {
        com.flowops.billing.service.EntitlementService entitlementService = mock(com.flowops.billing.service.EntitlementService.class);
        com.flowops.billing.model.ExecutionReservationTicket ticket = com.flowops.billing.model.ExecutionReservationTicket.reserved(java.time.Instant.now());
        when(entitlementService.reserveAiGeneration(any())).thenReturn(ticket);

        AiController c = new AiController(aiService, nodeRegistry, MAPPER, rateLimiter, entitlementService, validator);

        when(aiService.generateWorkflow(anyString()))
                .thenReturn(new AiServiceClient.Generation(false, null, null));

        GenerateWorkflowRequest req = new GenerateWorkflowRequest("Test prompt");
        ApiException ex = assertThrows(ApiException.class, () -> c.generate(req));
        assertThat(ex.code()).isEqualTo(ErrorCode.AI_NOT_CONFIGURED);

        org.mockito.Mockito.verify(entitlementService).refundAiGeneration(any(), org.mockito.ArgumentMatchers.eq(ticket));
    }

    @Test
    void generationFailureRefundsAiGenerationTicket() {
        com.flowops.billing.service.EntitlementService entitlementService = mock(com.flowops.billing.service.EntitlementService.class);
        com.flowops.billing.model.ExecutionReservationTicket ticket = com.flowops.billing.model.ExecutionReservationTicket.reserved(java.time.Instant.now());
        when(entitlementService.reserveAiGeneration(any())).thenReturn(ticket);

        AiController c = new AiController(aiService, nodeRegistry, MAPPER, rateLimiter, entitlementService, validator);

        when(aiService.generateWorkflow(anyString()))
                .thenThrow(new RuntimeException("AI provider timeout"));

        GenerateWorkflowRequest req = new GenerateWorkflowRequest("Test prompt");
        assertThrows(RuntimeException.class, () -> c.generate(req));

        org.mockito.Mockito.verify(entitlementService).refundAiGeneration(any(), org.mockito.ArgumentMatchers.eq(ticket));
    }

    @Test
    void successfulGenerationDoesNotRefundTicket() throws Exception {
        com.flowops.billing.service.EntitlementService entitlementService = mock(com.flowops.billing.service.EntitlementService.class);
        com.flowops.billing.model.ExecutionReservationTicket ticket = com.flowops.billing.model.ExecutionReservationTicket.reserved(java.time.Instant.now());
        when(entitlementService.reserveAiGeneration(any())).thenReturn(ticket);

        AiController c = new AiController(aiService, nodeRegistry, MAPPER, rateLimiter, entitlementService, validator);

        String json = """
        {
          "nodes": [
            {
              "id": "trigger",
              "type": "manual_trigger",
              "config": {}
            }
          ],
          "edges": []
        }
        """;
        when(aiService.generateWorkflow(anyString()))
                .thenReturn(new AiServiceClient.Generation(true, MAPPER.readTree(json), "ok"));

        GenerateWorkflowRequest req = new GenerateWorkflowRequest("Test prompt");
        GenerateWorkflowResponse resp = c.generate(req);

        assertThat(resp).isNotNull();
        org.mockito.Mockito.verify(entitlementService, org.mockito.Mockito.never()).refundAiGeneration(any(), any());
    }
}
