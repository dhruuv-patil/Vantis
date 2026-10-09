package com.flowops.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flowops.api.ReliabilityEnvelopes;
import com.flowops.common.error.ApiException;
import com.flowops.common.error.ErrorCode;
import com.flowops.config.AiProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The backend's sole gateway to the Python AI service. Uses the JDK
 * {@link HttpClient}
 * (mirrors {@code HttpRequestExecutor}: a connect timeout on the client and a
 * per-request
 * timeout on each call), presenting the shared {@code X-Service-Token}.
 *
 * <p>
 * The provider API key never passes through here — it lives only on the AI
 * service,
 * which returns {@code configured:false} rather than the key. Neither the token
 * nor any
 * request/response body is ever logged; only the target path and status/failure
 * class.
 * Any non-2xx response or transport failure becomes
 * {@link ErrorCode#AI_SERVICE_ERROR}.
 */
@Component
public class AiServiceClient implements AiCompleter {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private final HttpClient client;
    private final AiProperties properties;
    private final ObjectMapper mapper;

    public AiServiceClient(AiProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
    }

    /**
     * Ask the AI service to turn a natural-language prompt into a workflow graph.
     */
    public Generation generateWorkflow(String prompt) {
        ObjectNode body = mapper.createObjectNode();
        body.put("prompt", prompt);
        JsonNode response = post("/ai/generate-workflow", body);

        boolean configured = response.path("configured").asBoolean(false);
        JsonNode graph = response.get("graph");
        String notes = response.hasNonNull("notes") ? response.get("notes").asText() : null;
        return new Generation(configured, graph == null || graph.isNull() ? null : graph, notes);
    }

    /**
     * Run an agent (test console or an ai_agent node) with the given configuration.
     */
    public AgentRun runAgent(String instructions, String model, List<String> tools, String input) {
        ObjectNode body = mapper.createObjectNode();
        body.put("instructions", instructions == null ? "" : instructions);
        if (model != null && !model.isBlank()) {
            body.put("model", model);
        }
        ArrayNode toolsNode = body.putArray("tools");
        if (tools != null) {
            for (String tool : tools) {
                if (tool != null) {
                    toolsNode.add(tool);
                }
            }
        }
        if (input != null) {
            body.put("input", input);
        }

        JsonNode response = post("/ai/agent/run", body);

        boolean configured = response.path("configured").asBoolean(false);
        String output = response.hasNonNull("output") ? response.get("output").asText() : null;
        String usedModel = response.hasNonNull("model") ? response.get("model").asText() : null;

        List<AgentToolCall> calls = new ArrayList<>();
        JsonNode toolCalls = response.get("tool_calls");
        if (toolCalls != null && toolCalls.isArray()) {
            for (JsonNode call : toolCalls) {
                calls.add(new AgentToolCall(
                        call.path("name").asText(""),
                        call.get("arguments"),
                        call.path("result").asText("")));
            }
        }
        return new AgentRun(configured, output, calls, usedModel);
    }

    @Override
    public AiCompleter.Completion complete(
            String systemPrompt, String userPrompt, String model, boolean jsonMode) {
        ObjectNode body = mapper.createObjectNode();
        body.put("system_prompt", systemPrompt == null ? "" : systemPrompt);
        body.put("user_prompt", userPrompt == null ? "" : userPrompt);
        if (model != null && !model.isBlank()) {
            body.put("model", model);
        }
        body.put("json_mode", jsonMode);

        JsonNode response = post("/ai/complete", body);
        boolean configured = response.path("configured").asBoolean(false);
        String output = response.hasNonNull("output") ? response.get("output").asText() : null;
        String usedModel = response.hasNonNull("model") ? response.get("model").asText() : null;
        return new AiCompleter.Completion(configured, output, usedModel);
    }

    /** On-demand AI investigation of an anomaly (reliability pivot, M6). */
    public ReliabilityEnvelopes.AIInvestigationResult investigateAnomaly(Map<String, Object> evidencePackage) {
        ObjectNode body = mapper.valueToTree(evidencePackage);
        JsonNode response = post("/ai/investigate-anomaly", body);

        boolean configured = response.path("configured").asBoolean(false);
        String summary = response.hasNonNull("summary") ? response.get("summary").asText() : null;
        String impact = response.hasNonNull("impact") ? response.get("impact").asText() : null;
        double confidence = response.hasNonNull("confidence") ? response.get("confidence").asDouble() : 0.0;
        String model = response.hasNonNull("model") ? response.get("model").asText() : null;
        Instant generatedAt = Instant.now();

        List<ReliabilityEnvelopes.LikelyCause> likelyCauses = new ArrayList<>();
        JsonNode causesNode = response.get("likely_causes");
        if (causesNode != null && causesNode.isArray()) {
            for (JsonNode cause : causesNode) {
                likelyCauses.add(new ReliabilityEnvelopes.LikelyCause(
                        cause.path("cause").asText(""),
                        cause.path("category").asText(""),
                        cause.path("confidence").asDouble(0.0),
                        cause.path("uncertainty").asText("")));
            }
        }

        List<ReliabilityEnvelopes.EvidenceItem> evidence = new ArrayList<>();
        JsonNode evidenceNode = response.get("evidence");
        if (evidenceNode != null && evidenceNode.isArray()) {
            for (JsonNode item : evidenceNode) {
                evidence.add(new ReliabilityEnvelopes.EvidenceItem(
                        item.path("type").asText(""),
                        item.path("description").asText(""),
                        item.path("source").asText(""),
                        item.path("inference").asText("")));
            }
        }

        List<ReliabilityEnvelopes.RecommendedAction> recommendedActions = new ArrayList<>();
        JsonNode actionsNode = response.get("recommended_actions");
        if (actionsNode != null && actionsNode.isArray()) {
            for (JsonNode action : actionsNode) {
                recommendedActions.add(new ReliabilityEnvelopes.RecommendedAction(
                        action.path("action").asText(""),
                        action.path("rationale").asText(""),
                        action.path("risk").asText(""),
                        action.path("effort").asText("")));
            }
        }

        return new ReliabilityEnvelopes.AIInvestigationResult(
                summary, likelyCauses, evidence, impact, recommendedActions, confidence, configured, model,
                generatedAt);
    }

    private JsonNode post(String path, JsonNode body) {
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (Exception serialization) {
            throw new ApiException(ErrorCode.AI_SERVICE_ERROR);
        }

        String reqId = currentRequestId();
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl() + path))
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
        if (reqId != null && !reqId.isBlank()) {
            request.header(com.flowops.common.error.RequestIdFilter.HEADER, reqId);
        }
        if (!properties.token().isBlank()) {
            request.header("X-Service-Token", properties.token());
        }

        HttpResponse<String> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.AI_SERVICE_ERROR, interrupted);
        } catch (IOException unreachable) {
            log.warn("AI service call to {} failed: {} requestId={}", path, unreachable.getClass().getSimpleName(), reqId);
            throw new ApiException(ErrorCode.AI_SERVICE_ERROR, unreachable);
        }

        if (response.statusCode() == 422) {
            String safeDetail = "validation failure";
            try {
                JsonNode errJson = mapper.readTree(response.body());
                if (errJson.hasNonNull("detail")) {
                    safeDetail = errJson.get("detail").asText();
                } else if (errJson.hasNonNull("message")) {
                    safeDetail = errJson.get("message").asText();
                }
            } catch (Exception ignored) {
            }
            log.warn("AI service call to {} returned HTTP 422: {} requestId={}", path, safeDetail, reqId);
            if ("/ai/generate-workflow".equals(path)) {
                throw new ApiException(ErrorCode.AI_GENERATION_FAILED, new RuntimeException("AI service 422: " + safeDetail));
            }
            throw new ApiException(ErrorCode.AI_SERVICE_ERROR, new RuntimeException("AI service 422: " + safeDetail));
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            log.warn("AI service call to {} returned HTTP {}. requestId={}", path, response.statusCode(), reqId);
            throw new ApiException(ErrorCode.AI_SERVICE_ERROR);
        }
        try {
            return mapper.readTree(response.body());
        } catch (Exception unparseable) {
            log.warn("AI service returned an unparseable body from {} requestId={}", path, reqId);
            throw new ApiException(ErrorCode.AI_SERVICE_ERROR, unparseable);
        }
    }

    private String currentRequestId() {
        try {
            org.springframework.web.context.request.RequestAttributes attrs =
                    org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                return com.flowops.common.error.RequestIdFilter.currentRequestId(sra.getRequest());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Result of a generation call. {@code graph} is the raw model graph,
     * re-validated upstream.
     */
    public record Generation(boolean configured, JsonNode graph, String notes) {
    }

    /**
     * Result of an agent run. {@code output} is null when the service is not
     * configured.
     */
    public record AgentRun(boolean configured, String output, List<AgentToolCall> toolCalls, String model) {
    }

    /** One tool the model invoked during a run, for transparency in the UI. */
    public record AgentToolCall(String name, JsonNode arguments, String result) {
    }
}