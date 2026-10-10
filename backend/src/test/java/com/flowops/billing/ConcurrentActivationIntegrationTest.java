package com.flowops.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
class ConcurrentActivationIntegrationTest {

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

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(Organization.create("Concurrent Org " + UUID.randomUUID(), "conc-" + UUID.randomUUID().toString().substring(0, 8)));
        user = userRepository.save(UserAccount.create("user-" + UUID.randomUUID() + "@test.local", "pw", "Concurrent User"));
        principal = new FlowOpsPrincipal(user.getId(), UUID.randomUUID(), org.getId(), Role.OWNER, user.getEmail());

        // Org on FREE plan (activeWorkflows cap = 3)
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Subscription sub = Subscription.create(
                org.getId(),
                "FREE",
                "active",
                "USD",
                "US",
                "monthly",
                now.minus(5, ChronoUnit.DAYS),
                now.plus(25, ChronoUnit.DAYS));
        subscriptionRepository.save(sub);
        entitlementService.evictPlanCache(org.getId());

        // Seed 2 active workflows (cap - 1 = 3 - 1 = 2)
        for (int i = 1; i <= 2; i++) {
            ObjectNode graph = validGraph();
            Workflow wf = Workflow.create(org.getId(), user.getId(), "Pre-seeded WF " + i, "Desc", graph);
            wf.markPublished(1);
            wf = workflowRepository.save(wf);

            WorkflowVersion ver = WorkflowVersion.create(wf.getId(), 1, graph, "v1", user.getId());
            versionRepository.save(ver);
        }

        assertThat(workflowRepository.countActiveByOrganizationId(org.getId())).isEqualTo(2L);
        assertThat(PlanCatalog.FREE_ACTIVE_WORKFLOWS).isEqualTo(3);
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
    @DisplayName("Two concurrent activations at cap-1 allow exactly one and block the second with PLAN_LIMIT_EXCEEDED")
    void concurrentActivationAtCapMinusOneAllowsExactlyOne() throws Exception {
        // Create 2 distinct draft workflows to be activated concurrently
        Workflow draft1 = workflowRepository.save(Workflow.create(org.getId(), user.getId(), "Draft WF 1", "Desc", validGraph()));
        Workflow draft2 = workflowRepository.save(Workflow.create(org.getId(), user.getId(), "Draft WF 2", "Desc", validGraph()));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch readyLatch = new CountDownLatch(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger blockedCount = new AtomicInteger();
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        PublishWorkflowRequest req = new PublishWorkflowRequest("Concurrent publish");

        Future<?> f1 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                workflowService.publish(principal, draft1.getId(), req);
                successCount.incrementAndGet();
            } catch (ApiException e) {
                if (e.code() == ErrorCode.PLAN_LIMIT_EXCEEDED) {
                    blockedCount.incrementAndGet();
                } else {
                    errors.add(e);
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        });

        Future<?> f2 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                workflowService.publish(principal, draft2.getId(), req);
                successCount.incrementAndGet();
            } catch (ApiException e) {
                if (e.code() == ErrorCode.PLAN_LIMIT_EXCEEDED) {
                    blockedCount.incrementAndGet();
                } else {
                    errors.add(e);
                }
            } catch (Throwable t) {
                errors.add(t);
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Fire both threads concurrently

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(errors).isEmpty();
        // Exactly one activation succeeded, exactly one blocked at cap
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(blockedCount.get()).isEqualTo(1);

        // Final count of active workflows in DB is exactly the cap (3)
        assertThat(workflowRepository.countActiveByOrganizationId(org.getId())).isEqualTo(3L);
    }
}
