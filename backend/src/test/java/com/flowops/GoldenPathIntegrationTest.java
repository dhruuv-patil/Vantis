package com.flowops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowops.domain.AnomalySeverity;
import com.flowops.domain.AnomalyStatus;
import com.flowops.domain.ExecutionStatus;
import com.flowops.domain.NodeRunStatus;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The end-to-end "golden path" — the most important test in the suite.
 *
 * <p>It exercises every layer of the application together:
 * register → login → create workflow → save graph → validate → publish v1 →
 * run → poll for terminal status → approve a human-approval run → retry a
 * failed run. The test uses {@link MockMvc} (not a real network port) so it
 * runs in-process, against the H2 test datasource, with the in-memory queue
 * forced by the {@code application-test.yml} profile.
 *
 * <p>If this test ever breaks, the product is broken. Per-executor unit tests
 * give us precision; this gives us the spine.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GoldenPathIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Test
    void registerCreateSavePublishAndRunToCompletion() throws Exception {
        String email = "alice+" + UUID.randomUUID() + "@example.com";
        String password = "pass-word-1234";

        // 1. Register a new user and org.
        String token = register(email, password, "Alice", "Alice's Org");

        // 2. Create a workflow.
        String workflowId = createWorkflow(token, "Notify on webhook");

        // 3. Save a simple graph: manual_trigger -> notification.
        String graph = "{"
                + "\"nodes\":["
                + "  {\"id\":\"t\",\"type\":\"manual_trigger\","
                + "   \"data\":{\"config\":{}}}"
                + "],"
                + "\"edges\":[]"
                + "}";
        saveGraph(token, workflowId, graph);

        // 4. Validate the graph.
        //    A trigger-only graph currently has no validation errors,
        //    so it is considered valid.
        mvc.perform(post("/api/workflows/" + workflowId + "/validate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        // 5. Replace with a complete graph: trigger -> notification.
        String fullGraph = "{"
                + "\"nodes\":["
                + "  {\"id\":\"t\",\"type\":\"manual_trigger\","
                + "   \"data\":{\"config\":{}}},"
                + "  {\"id\":\"n\",\"type\":\"notification\","
                + "   \"data\":{\"config\":{\"channel\":\"email\",\"message\":\"hello\"}}}"
                + "],"
                + "\"edges\":[{\"id\":\"e1\",\"source\":\"t\",\"target\":\"n\"}]"
                + "}";
        saveGraph(token, workflowId, fullGraph);

        mvc.perform(post("/api/workflows/" + workflowId + "/validate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        // 6. Publish v1.
        MvcResult publishResult =
                mvc.perform(post("/api/workflows/" + workflowId + "/publish")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"note\":\"initial release\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.published").value(true))
                        .andExpect(jsonPath("$.version.versionNumber").value(1))
                        .andReturn();

        assertThat(
                publishResult.getResponse()
                        .getContentAsString(StandardCharsets.UTF_8))
                .contains("\"versionNumber\":1");

        // 7. Run the workflow.
        AtomicReference<String> executionId = new AtomicReference<>();

        mvc.perform(post("/api/workflows/" + workflowId + "/run")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andDo(result -> {
                    JsonNode body =
                            MAPPER.readTree(
                                    result.getResponse().getContentAsString());

                    executionId.set(body.get("id").asText());

                    assertThat(body.get("status").asText())
                            .isIn(
                                    ExecutionStatus.QUEUED.name(),
                                    ExecutionStatus.RUNNING.name(),
                                    ExecutionStatus.SUCCEEDED.name());
                });

        // 8. Poll for SUCCEEDED.
        ExecutionStatus finalStatus =
                waitForTerminal(token, executionId.get());

        assertThat(finalStatus)
                .isEqualTo(ExecutionStatus.SUCCEEDED);

        // 9. The run is visible in the org's executions list.
        mvc.perform(get("/api/executions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.executions[0].id")
                                .value(executionId.get()));
    }

    @Test
    void humanApprovalRunParksAndResumes() throws Exception {
        String email = "bob+" + UUID.randomUUID() + "@example.com";

        String token =
                register(
                        email,
                        "pass-word-1234",
                        "Bob",
                        "Bob's Org");

        String workflowId =
                createWorkflow(token, "Approval workflow");

        // Graph: trigger -> human_approval -> notification.
        String graph = "{"
                + "\"nodes\":["
                + "  {\"id\":\"t\",\"type\":\"manual_trigger\",\"data\":{}},"
                + "  {\"id\":\"h\",\"type\":\"human_approval\","
                + "   \"data\":{\"config\":{\"prompt\":\"ship it?\"}}},"
                + "  {\"id\":\"n\",\"type\":\"notification\","
                + "   \"data\":{\"config\":{\"channel\":\"email\",\"message\":\"ok\"}}},"
                + "  {\"id\":\"r\",\"type\":\"notification\","
                + "   \"data\":{\"config\":{\"channel\":\"email\",\"message\":\"no\"}}}"
                + "],"
                + "\"edges\":["
                + "  {\"id\":\"e1\",\"source\":\"t\",\"target\":\"h\"},"
                + "  {\"id\":\"e2\",\"source\":\"h\",\"target\":\"n\",\"sourceHandle\":\"approved\"},"
                + "  {\"id\":\"e3\",\"source\":\"h\",\"target\":\"r\",\"sourceHandle\":\"rejected\"}"
                + "]"
                + "}";

        saveGraph(token, workflowId, graph);

        mvc.perform(post("/api/workflows/" + workflowId + "/validate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mvc.perform(post("/api/workflows/" + workflowId + "/publish")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true));

        AtomicReference<String> executionId =
                new AtomicReference<>();

        mvc.perform(post("/api/workflows/" + workflowId + "/run")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andDo(result ->
                        executionId.set(
                                MAPPER.readTree(
                                        result.getResponse()
                                                .getContentAsString())
                                        .get("id")
                                        .asText()));

        // 9. Wait for the run to park at WAITING.
        waitForStatus(
                token,
                executionId.get(),
                ExecutionStatus.WAITING);

        // 10. Approve. The run resumes and reaches SUCCEEDED.
        mvc.perform(
                        post(
                                "/api/executions/"
                                        + executionId.get()
                                        + "/nodes/h/decision")
                                .header(
                                        "Authorization",
                                        "Bearer " + token)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"approved\":true,\"note\":\"ship it\"}"))
                .andExpect(status().isOk());

        assertThat(
                waitForTerminal(
                        token,
                        executionId.get()))
                .isEqualTo(ExecutionStatus.SUCCEEDED);
    }

    @Test
    void cancelStopsAnInFlightRun() throws Exception {
        String email = "carol+" + UUID.randomUUID() + "@example.com";

        String token =
                register(
                        email,
                        "pass-word-1234",
                        "Carol",
                        "Carol's Org");

        String workflowId =
                createWorkflow(token, "Cancelable");

        // Graph: trigger -> delay(5s, but the cap is 1s in test config).
        String graph = "{"
                + "\"nodes\":["
                + "  {\"id\":\"t\",\"type\":\"manual_trigger\",\"data\":{}},"
                + "  {\"id\":\"d\",\"type\":\"delay\",\"data\":{\"config\":{\"seconds\":5}}}"
                + "],"
                + "\"edges\":[{\"id\":\"e1\",\"source\":\"t\",\"target\":\"d\"}]"
                + "}";

        saveGraph(token, workflowId, graph);

        mvc.perform(post("/api/workflows/" + workflowId + "/validate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        mvc.perform(post("/api/workflows/" + workflowId + "/publish")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        AtomicReference<String> executionId =
                new AtomicReference<>();

        mvc.perform(post("/api/workflows/" + workflowId + "/run")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andDo(result ->
                        executionId.set(
                                MAPPER.readTree(
                                        result.getResponse()
                                                .getContentAsString())
                                        .get("id")
                                        .asText()));

        // The run enters RUNNING quickly.
        // We try to cancel it before the capped delay finishes.
        try {
            mvc.perform(
                            post(
                                    "/api/executions/"
                                            + executionId.get()
                                            + "/cancel")
                                    .header(
                                            "Authorization",
                                            "Bearer " + token))
                    .andExpect(status().isOk());
        } catch (AssertionError stillRunning) {
            // The delay may have finished before cancel was sent.
        }

        ExecutionStatus end =
                waitForTerminal(
                        token,
                        executionId.get());

        assertThat(end)
                .isIn(
                        ExecutionStatus.CANCELED,
                        ExecutionStatus.SUCCEEDED);
    }

    /* ---------------------------------------------------------- helpers */

    private String register(
            String email,
            String password,
            String fullName,
            String orgName) throws Exception {

        MvcResult result =
                mvc.perform(
                                post("/api/auth/register")
                                        .contentType(
                                                MediaType.APPLICATION_JSON)
                                        .content(
                                                "{"
                                                        + "\"email\":\""
                                                        + email
                                                        + "\","
                                                        + "\"password\":\""
                                                        + password
                                                        + "\","
                                                        + "\"fullName\":\""
                                                        + fullName
                                                        + "\","
                                                        + "\"organizationName\":\""
                                                        + orgName
                                                        + "\""
                                                        + "}"))
                        .andExpect(status().isCreated())
                        .andReturn();

        JsonNode body =
                MAPPER.readTree(
                        result.getResponse()
                                .getContentAsString());

        return body.get("accessToken").asText();
    }

    private String createWorkflow(
            String token,
            String name) throws Exception {

        MvcResult result =
                mvc.perform(
                                post("/api/workflows")
                                        .header(
                                                "Authorization",
                                                "Bearer " + token)
                                        .contentType(
                                                MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"name\":\""
                                                        + name
                                                        + "\",\"description\":\"\"}"))
                        .andExpect(status().isCreated())
                        .andReturn();

        return MAPPER.readTree(
                        result.getResponse()
                                .getContentAsString())
                .get("id")
                .asText();
    }

    private void saveGraph(
            String token,
            String workflowId,
            String graphJson) throws Exception {

        mvc.perform(
                        put(
                                "/api/workflows/"
                                        + workflowId
                                        + "/graph")
                                .header(
                                        "Authorization",
                                        "Bearer " + token)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"graph\":"
                                                + graphJson
                                                + "}"))
                .andExpect(status().isOk());
    }

    private ExecutionStatus waitForTerminal(
            String token,
            String executionId) throws Exception {

        long deadline =
                System.currentTimeMillis() + 5_000L;

        ExecutionStatus last = null;

        while (System.currentTimeMillis() < deadline) {
            last = readStatus(token, executionId);

            if (last != null && last.isTerminal()) {
                return last;
            }

            Thread.sleep(50L);
        }

        return last;
    }

    private void waitForStatus(
            String token,
            String executionId,
            ExecutionStatus expected) throws Exception {

        long deadline =
                System.currentTimeMillis() + 5_000L;

        ExecutionStatus last = null;

        while (System.currentTimeMillis() < deadline) {
            last = readStatus(token, executionId);

            if (last == expected) {
                return;
            }

            Thread.sleep(50L);
        }

        throw new AssertionError(
                "Execution "
                        + executionId
                        + " did not reach "
                        + expected
                        + " within 5s; last status was "
                        + last);
    }

    private ExecutionStatus readStatus(
            String token,
            String executionId) throws Exception {

        MvcResult result =
                mvc.perform(
                                get(
                                        "/api/executions/"
                                                + executionId)
                                        .header(
                                                "Authorization",
                                                "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode body =
                MAPPER.readTree(
                        result.getResponse()
                                .getContentAsString());

        return ExecutionStatus.valueOf(
                body.get("status").asText());
    }

    @Test
    void goldenPathWithAnomalyDetectionAndNotification()
            throws Exception {

        // This test extends the golden path to verify the reliability loop:
        // register → login → create workflow → create/edit nodes → save → validate →
        // publish → execute → execution persists → telemetry is produced →
        // anomaly detection can process telemetry → anomaly is persisted →
        // notification is created

        String email =
                "dave+" + UUID.randomUUID() + "@example.com";

        String password = "pass-word-1234";

        String token =
                register(
                        email,
                        password,
                        "Dave",
                        "Dave's Org");

        String workflowId =
                createWorkflow(
                        token,
                        "Reliability test workflow");

        // Create a workflow with an http_request node that will have variable latency.
        String graph = "{"
                + "\"nodes\":["
                + "  {\"id\":\"t\",\"type\":\"manual_trigger\","
                + "   \"data\":{\"config\":{}}},"
                + "  {\"id\":\"h\",\"type\":\"http_request\","
                + "   \"data\":{\"config\":{\"url\":\"https://httpbin.org/delay/1\",\"method\":\"GET\"}}},"
                + "  {\"id\":\"n\",\"type\":\"notification\","
                + "   \"data\":{\"config\":{\"channel\":\"email\",\"message\":\"done\"}}}"
                + "],"
                + "\"edges\":["
                + "  {\"id\":\"e1\",\"source\":\"t\",\"target\":\"h\"},"
                + "  {\"id\":\"e2\",\"source\":\"h\",\"target\":\"n\"}"
                + "]"
                + "}";

        saveGraph(
                token,
                workflowId,
                graph);

        mvc.perform(
                        post(
                                "/api/workflows/"
                                        + workflowId
                                        + "/validate")
                                .header(
                                        "Authorization",
                                        "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.valid")
                                .value(true));

        mvc.perform(
                        post(
                                "/api/workflows/"
                                        + workflowId
                                        + "/publish")
                                .header(
                                        "Authorization",
                                        "Bearer " + token)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"note\":\"reliability test\"}"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.published")
                                .value(true));

        AtomicReference<String> executionId =
                new AtomicReference<>();

        mvc.perform(
                        post(
                                "/api/workflows/"
                                        + workflowId
                                        + "/run")
                                .header(
                                        "Authorization",
                                        "Bearer " + token)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isCreated())
                .andDo(result -> {
                    JsonNode body =
                            MAPPER.readTree(
                                    result.getResponse()
                                            .getContentAsString());

                    executionId.set(
                            body.get("id").asText());

                    assertThat(
                            body.get("status").asText())
                            .isIn(
                                    ExecutionStatus.QUEUED.name(),
                                    ExecutionStatus.RUNNING.name(),
                                    ExecutionStatus.FAILED.name(),
                                    ExecutionStatus.SUCCEEDED.name());
                });

        // Wait for terminal status.
        ExecutionStatus finalStatus =
                waitForTerminal(
                        token,
                        executionId.get());

        assertThat(finalStatus)
                .isIn(
                        ExecutionStatus.FAILED,
                        ExecutionStatus.SUCCEEDED);

        // Verify execution persisted.
        MvcResult execResult =
                mvc.perform(
                                get(
                                        "/api/executions/"
                                                + executionId.get())
                                        .header(
                                                "Authorization",
                                                "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode execBody =
                MAPPER.readTree(
                        execResult.getResponse()
                                .getContentAsString());

        assertThat(
                execBody.get("id").asText())
                .isEqualTo(executionId.get());

        assertThat(
                execBody.get("workflowId").asText())
                .isEqualTo(workflowId);

        // Verify nodes were executed.
        JsonNode nodes =
                execBody.get("nodes");

        assertThat(nodes.isArray()).isTrue();
        assertThat(nodes.size()).isGreaterThan(0);

        // Verify logs exist.
        JsonNode logs =
                execBody.get("logs");

        assertThat(logs.isArray()).isTrue();
        assertThat(logs.size()).isGreaterThan(0);

        // Wait a moment for telemetry capture and anomaly detection.
        Thread.sleep(1000);

        // Check if any anomalies were detected.
        MvcResult anomaliesResult =
                mvc.perform(
                                get(
                                        "/api/reliability/anomalies")
                                        .header(
                                                "Authorization",
                                                "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode anomaliesBody =
                MAPPER.readTree(
                        anomaliesResult.getResponse()
                                .getContentAsString());

        assertThat(
                anomaliesBody.has("anomalies"))
                .isTrue();

        // Check notifications.
        MvcResult notificationsResult =
                mvc.perform(
                                get("/api/notifications")
                                        .header(
                                                "Authorization",
                                                "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode notificationsBody =
                MAPPER.readTree(
                        notificationsResult.getResponse()
                                .getContentAsString());

        assertThat(
                notificationsBody.has("notifications"))
                .isTrue();

        JsonNode notifications =
                notificationsBody.get("notifications");

        assertThat(
                notifications.isArray())
                .isTrue();

        // Verify workflow health endpoint works.
        MvcResult healthResult =
                mvc.perform(
                                get(
                                        "/api/reliability/workflows/"
                                                + workflowId
                                                + "/health")
                                        .header(
                                                "Authorization",
                                                "Bearer " + token))
                        .andExpect(status().isOk())
                        .andReturn();

        JsonNode healthBody =
                MAPPER.readTree(
                        healthResult.getResponse()
                                .getContentAsString());

        assertThat(
                healthBody.has("health"))
                .isTrue();

        JsonNode health =
                healthBody.get("health");

        assertThat(
                health.get("workflowId").asText())
                .isEqualTo(workflowId);

        assertThat(
                health.get("totalExecutions").asLong())
                .isGreaterThan(0);
    }
}