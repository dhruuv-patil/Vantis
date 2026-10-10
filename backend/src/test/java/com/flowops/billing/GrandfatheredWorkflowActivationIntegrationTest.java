package com.flowops.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.api.WorkflowEnvelopes;
import com.flowops.billing.model.PlanCatalog;
import com.flowops.billing.service.EntitlementService;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.domain.Organization;
import com.flowops.domain.Role;
import com.flowops.domain.Subscription;
import com.flowops.domain.UserAccount;
import com.flowops.domain.Workflow;
import com.flowops.domain.WorkflowVersion;
import com.flowops.repository.OrganizationRepository;
import com.flowops.repository.SubscriptionRepository;
import com.flowops.repository.UserRepository;
import com.flowops.repository.WorkflowRepository;
import com.flowops.repository.WorkflowVersionRepository;
import com.flowops.security.FlowOpsPrincipal;
import com.flowops.workflow.PublishWorkflowRequest;
import com.flowops.workflow.WorkflowService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "flowops.entitlements.enforcement=enforce"
})
class GrandfatheredWorkflowActivationIntegrationTest {

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowVersionRepository versionRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private ObjectMapper objectMapper;

    private Organization org;
    private UserAccount user;
    private FlowOpsPrincipal principal;
    private final List<Workflow> seededWorkflows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(Organization.create("Grandfather Org " + UUID.randomUUID(), "gf-" + UUID.randomUUID().toString().substring(0, 8)));
        user = userRepository.save(UserAccount.create("user-" + UUID.randomUUID() + "@test.local", "hashedpassword", "Grandfather User"));
        principal = new FlowOpsPrincipal(user.getId(), UUID.randomUUID(), org.getId(), Role.OWNER, user.getEmail());

        // Org on PRIVATE_BETA plan (activeWorkflows cap = 10)
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Subscription sub = Subscription.create(
                org.getId(),
                "PRIVATE_BETA",
                "active",
                "USD",
                "US",
                "monthly",
                now.minus(5, ChronoUnit.DAYS),
                now.plus(25, ChronoUnit.DAYS));
        subscriptionRepository.save(sub);
        entitlementService.evictPlanCache(org.getId());

        // Seed 12 already-active workflows under the 10 cap (grandfathered state)
        for (int i = 1; i <= 12; i++) {
            ObjectNode graph = validGraph();
            Workflow wf = Workflow.create(org.getId(), user.getId(), "Active WF " + i, "Desc", graph);
            wf.markPublished(1);
            wf = workflowRepository.save(wf);

            WorkflowVersion ver = WorkflowVersion.create(wf.getId(), 1, graph, "v1", user.getId());
            versionRepository.save(ver);
            seededWorkflows.add(wf);
        }

        assertThat(workflowRepository.countActiveByOrganizationId(org.getId())).isEqualTo(12L);
        assertThat(PlanCatalog.BETA_ACTIVE_WORKFLOWS).isEqualTo(10);
    }

    @AfterEach
    void tearDown() {
        if (org != null) {
            versionRepository.deleteAll();
            workflowRepository.deleteAll();
            subscriptionRepository.deleteAll();
            userRepository.deleteById(user.getId());
            organizationRepository.deleteById(org.getId());
        }
    }

    private ObjectNode validGraph() {
        ObjectNode graph = objectMapper.createObjectNode();
        var nodes = graph.putArray("nodes");
        var n1 = nodes.addObject();
        n1.put("id", "t");
        n1.put("type", "manual_trigger");
        n1.putObject("data").putObject("config");
        graph.putArray("edges");
        return graph;
    }

    @Test
    @DisplayName("Grandfathered org with 12 active workflows under 10 cap: republish existing active workflow allowed, 13th activation blocked")
    void grandfatheredActiveWorkflowsRepublishAllowed13thActivationBlocked() {
        // 1. Republish an already active grandfathered workflow -> MUST BE ALLOWED
        Workflow grandfatheredWf = seededWorkflows.get(0);
        PublishWorkflowRequest republishReq = new PublishWorkflowRequest("Updated v2 note");
        WorkflowEnvelopes.PublishResult result = workflowService.publish(principal, grandfatheredWf.getId(), republishReq);

        assertThat(result.validation().valid()).isTrue();
        assertThat(result.version()).isNotNull();
        assertThat(result.version().versionNumber()).isEqualTo(2);

        // Count of active workflows remains 12
        assertThat(workflowRepository.countActiveByOrganizationId(org.getId())).isEqualTo(12L);

        // 2. Attempt to activate a NEW 13th workflow -> MUST BE BLOCKED
        Workflow draftWf = Workflow.create(org.getId(), user.getId(), "13th Workflow Draft", "Desc", validGraph());
        draftWf = workflowRepository.save(draftWf);

        final UUID draftId = draftWf.getId();
        PublishWorkflowRequest newPublishReq = new PublishWorkflowRequest("Initial publication");

        assertThatThrownBy(() -> workflowService.publish(principal, draftId, newPublishReq))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException apiEx = (ApiException) ex;
                    assertThat(apiEx.code()).isEqualTo(ErrorCode.PLAN_LIMIT_EXCEEDED);
                    assertThat(apiEx.details()).isNotNull();
                    assertThat(apiEx.details().limitKey()).isEqualTo("activeWorkflows");
                    assertThat(apiEx.details().current()).isEqualTo(12);
                    assertThat(apiEx.details().max()).isEqualTo(PlanCatalog.BETA_ACTIVE_WORKFLOWS);
                    assertThat(apiEx.details().planId()).isEqualTo("PRIVATE_BETA");
                });

        // Still exactly 12 active workflows in DB
        assertThat(workflowRepository.countActiveByOrganizationId(org.getId())).isEqualTo(12L);
    }
}
