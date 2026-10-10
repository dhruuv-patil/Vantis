package com.flowops.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.api.ExecutionDetailResponse;
import com.flowops.billing.service.EntitlementService;
import com.flowops.billing.service.UsageMeterService;
import com.flowops.domain.Organization;
import com.flowops.domain.Role;
import com.flowops.domain.Subscription;
import com.flowops.domain.UserAccount;
import com.flowops.domain.Workflow;
import com.flowops.domain.WorkflowExecution;
import com.flowops.domain.WorkflowVersion;
import com.flowops.execution.ExecutionService;
import com.flowops.execution.RunWorkflowRequest;
import com.flowops.repository.IdempotencyKeyRepository;
import com.flowops.repository.OrganizationRepository;
import com.flowops.repository.SubscriptionRepository;
import com.flowops.repository.UserRepository;
import com.flowops.repository.WorkflowExecutionRepository;
import com.flowops.repository.WorkflowRepository;
import com.flowops.repository.WorkflowVersionRepository;
import com.flowops.security.FlowOpsPrincipal;
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
class ConcurrentIdempotentExecutionIntegrationTest {

    @Autowired
    private ExecutionService executionService;

    @Autowired
    private WorkflowRepository workflowRepository;

    @Autowired
    private WorkflowVersionRepository versionRepository;

    @Autowired
    private WorkflowExecutionRepository executionRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private EntitlementService entitlementService;

    @Autowired
    private UsageMeterService usageMeterService;

    @Autowired
    private ObjectMapper objectMapper;

    private Organization org;
    private UserAccount user;
    private Workflow workflow;
    private WorkflowVersion version;
    private FlowOpsPrincipal principal;
    private Instant periodStart;

    @BeforeEach
    void setUp() {
        org = organizationRepository.save(Organization.create("Idemp Org " + UUID.randomUUID(), "idm-" + UUID.randomUUID().toString().substring(0, 8)));
        user = userRepository.save(UserAccount.create("user-" + UUID.randomUUID() + "@test.local", "pw", "Idemp User"));
        principal = new FlowOpsPrincipal(user.getId(), UUID.randomUUID(), org.getId(), Role.OWNER, user.getEmail());

        periodStart = Instant.now().minus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);
        Subscription sub = Subscription.create(
                org.getId(),
                "PRO",
                "active",
                "USD",
                "US",
                "monthly",
                periodStart,
                periodStart.plus(30, ChronoUnit.DAYS));
        subscriptionRepository.save(sub);
        entitlementService.evictPlanCache(org.getId());

        ObjectNode graph = objectMapper.createObjectNode();
        var nodes = graph.putArray("nodes");
        var n1 = nodes.addObject();
        n1.put("id", "trigger");
        n1.put("type", "manual_trigger");
        n1.putObject("config");

        workflow = Workflow.create(org.getId(), user.getId(), "Idempotent WF", "Desc", graph);
        workflow.markPublished(1);
        workflow = workflowRepository.save(workflow);

        version = WorkflowVersion.create(workflow.getId(), 1, graph, "v1", user.getId());
        version = versionRepository.save(version);
    }

    @AfterEach
    void tearDown() {
        if (org != null) {
            idempotencyKeyRepository.deleteAll();
            executionRepository.deleteAll();
            versionRepository.deleteAll();
            workflowRepository.deleteAll();
            subscriptionRepository.deleteAll();
            userRepository.deleteById(user.getId());
            organizationRepository.deleteById(org.getId());
        }
    }

    @Test
    @DisplayName("Concurrent execution requests with same idempotencyKey yield exactly 1 execution and usage +1")
    void concurrentIdempotentRunsYieldOneExecutionAndUsagePlusOne() throws Exception {
        int threads = 8;
        String idempotencyKey = "shared-idem-key-" + UUID.randomUUID();
        ObjectNode input = objectMapper.createObjectNode();
        input.put("idempotencyKey", idempotencyKey);
        RunWorkflowRequest request = new RunWorkflowRequest(input);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<ExecutionDetailResponse> responses = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    ExecutionDetailResponse resp = executionService.run(principal, workflow.getId(), request);
                    responses.add(resp);
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Release all threads concurrently

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(errors).isEmpty();
        assertThat(responses).hasSize(threads);

        // 1. All concurrent callers received the exact same execution ID
        UUID firstId = responses.get(0).id();
        for (ExecutionDetailResponse resp : responses) {
            assertThat(resp.id()).isEqualTo(firstId);
        }

        // 2. Exactly 1 execution row exists in the database
        List<WorkflowExecution> dbExecutions = executionRepository.findAll();
        assertThat(dbExecutions).hasSize(1);
        assertThat(dbExecutions.get(0).getId()).isEqualTo(firstId);

        // 3. Usage counter in DB incremented by exactly 1
        long currentUsage = usageMeterService.getCurrentUsage(org.getId(), periodStart, "executions");
        assertThat(currentUsage).isEqualTo(1L);
    }
}
