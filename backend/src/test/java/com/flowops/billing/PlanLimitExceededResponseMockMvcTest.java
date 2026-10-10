package com.flowops.billing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.billing.service.EntitlementService;
import com.flowops.billing.service.RejectedExecutionRecorder;
import com.flowops.common.error.ApiException;
import com.flowops.domain.Role;
import com.flowops.security.JwtService;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlanLimitExceededResponseMockMvcTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.flowops.repository.UserRepository users;

    @MockBean
    private EntitlementService entitlementService;

    @MockBean
    private RejectedExecutionRecorder rejectedRecorder;

    @MockBean
    private com.flowops.repository.WorkflowRepository workflowRepository;

    @MockBean
    private com.flowops.repository.WorkflowVersionRepository versionRepository;

    @Test
    @DisplayName("PLAN_LIMIT_EXCEEDED error returns structured machine-readable payload in HTTP JSON body")
    void planLimitExceededReturnsStructuredJsonPayload() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();

        // Prepare a verified, authenticated user so authentication succeeds before entitlement check
        com.flowops.domain.UserAccount verifiedUser = com.flowops.domain.UserAccount.create(
                "verified+" + UUID.randomUUID() + "@example.com",
                "hashedPassword",
                "Verified User");
        verifiedUser.markEmailVerified();
        verifiedUser = users.save(verifiedUser);
        UUID userId = verifiedUser.getId();

        String token = jwtService.issueAccessToken(userId, UUID.randomUUID(), orgId, Role.ADMIN, verifiedUser.getEmail());

        var graph = objectMapper.createObjectNode();
        var nodes = graph.putArray("nodes");
        var n = nodes.addObject();
        n.put("id", "t");
        n.put("type", "manual_trigger");
        n.putObject("data").putObject("config");
        graph.putArray("edges");

        com.flowops.domain.Workflow workflow = com.flowops.domain.Workflow.create(orgId, userId, "Test Workflow", "Desc", graph);
        workflow.markPublished(1);
        org.springframework.test.util.ReflectionTestUtils.setField(workflow, "id", workflowId);
        org.mockito.Mockito.when(workflowRepository.findByIdAndOrganizationId(workflowId, orgId)).thenReturn(java.util.Optional.of(workflow));

        com.flowops.domain.WorkflowVersion version = com.flowops.domain.WorkflowVersion.create(workflowId, 1, graph, "v1", userId);
        org.mockito.Mockito.when(versionRepository.findByWorkflowIdAndVersionNumber(workflowId, 1)).thenReturn(java.util.Optional.of(version));

        // Mock EntitlementService to throw structured PLAN_LIMIT_EXCEEDED
        doThrow(ApiException.planLimitExceeded(
                "executionsPerMonth",
                1000,
                1000,
                "FREE",
                "PRO",
                "Monthly execution limit reached for your plan (1000 / 1000)."))
                .when(entitlementService).reserveExecution(any());

        mvc.perform(post("/api/workflows/" + workflowId + "/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"input\":{}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("PLAN_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.error.message").value("Monthly execution limit reached for your plan (1000 / 1000)."))
                .andExpect(jsonPath("$.error.status").value(403))
                .andExpect(jsonPath("$.error.details.limitKey").value("executionsPerMonth"))
                .andExpect(jsonPath("$.error.details.current").value(1000))
                .andExpect(jsonPath("$.error.details.max").value(1000))
                .andExpect(jsonPath("$.error.details.planId").value("FREE"))
                .andExpect(jsonPath("$.error.details.suggestedPlan").value("PRO"));
    }

    @Test
    @DisplayName("Unverified user executing workflow run is rejected with 403 EMAIL_NOT_VERIFIED before entitlement check")
    void unverifiedUserExecutingWorkflowReturnsEmailNotVerified() throws Exception {
        UUID orgId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();

        com.flowops.domain.UserAccount unverifiedUser = com.flowops.domain.UserAccount.create(
                "unverified+" + UUID.randomUUID() + "@example.com",
                "hashedPassword",
                "Unverified User");
        unverifiedUser = users.save(unverifiedUser);

        String token = jwtService.issueAccessToken(
                unverifiedUser.getId(), UUID.randomUUID(), orgId, Role.ADMIN, unverifiedUser.getEmail());

        mvc.perform(post("/api/workflows/" + workflowId + "/run")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"input\":{}}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("EMAIL_NOT_VERIFIED"));
    }
}
