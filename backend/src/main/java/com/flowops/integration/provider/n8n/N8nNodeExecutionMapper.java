package com.flowops.integration.provider.n8n;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowops.integration.provider.ExecutionStatus;
import com.flowops.integration.provider.ExternalNodeExecution;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Maps n8n execution data (node results) to normalized {@link ExternalNodeExecution} records.
 *
 * <p>Handles:
 * <ul>
 *   <li>Precise item counts across single, multi-item, and 0-item outputs.</li>
 *   <li>Zero-PII structural schema signatures with stable hashing and depth/key bounds.</li>
 *   <li>Error classification and HTTP status extraction.</li>
 *   <li>Multi-run nodes (loops and retries) with preserved run indices.</li>
 *   <li>Subworkflow execution linking.</li>
 *   <li>Pruned or truncated payload handling without failures.</li>
 * </ul>
 */
public final class N8nNodeExecutionMapper {

    private static final int MAX_SCHEMA_DEPTH = 3;
    private static final int MAX_SCHEMA_KEYS = 50;

    private N8nNodeExecutionMapper() {
    }

    /**
     * Converts n8n get-execution-data response to normalized node executions.
     */
    public static List<ExternalNodeExecution> toExternal(
            JsonNode response, String executionExternalId, String workflowExternalId) {

        List<ExternalNodeExecution> nodes = new ArrayList<>();
        JsonNode resultData = findResultData(response);
        JsonNode runData = resultData != null ? resultData.path("runData") : null;
        String lastNodeExecuted = resultData != null ? resultData.path("lastNodeExecuted").asText(null) : null;
        JsonNode executionError = resultData != null ? resultData.path("error") : null;

        if (runData != null && runData.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = runData.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                String nodeName = entry.getKey();
                JsonNode nodeRuns = entry.getValue();

                if (nodeRuns.isArray()) {
                    int runCount = nodeRuns.size();
                    for (int i = 0; i < runCount; i++) {
                        JsonNode run = nodeRuns.get(i);
                        nodes.add(toExternal(
                                run,
                                nodeName,
                                executionExternalId,
                                workflowExternalId,
                                i,
                                runCount,
                                lastNodeExecuted,
                                executionError));
                    }
                }
            }
        }

        // If no runData, fall back to a single pseudo-node representing the execution
        if (nodes.isEmpty()) {
            JsonNode dataNode = response.path("data");
            String statusRaw = dataNode.path("status").asText(response.path("status").asText(""));
            ExecutionStatus overall = N8nExecutionMapper.mapStatus(statusRaw);

            String errorCode = null;
            String errorMessage = null;
            Integer httpStatus = null;

            JsonNode errorNode = resultData != null ? resultData.path("error") : null;
            if (errorNode == null || errorNode.isMissingNode() || errorNode.isNull()) {
                errorNode = dataNode.path("error");
            }
            if (errorNode != null && errorNode.isObject() && !errorNode.isEmpty()) {
                errorCode = errorNode.path("name").asText("ExecutionError");
                errorMessage = errorNode.path("message").asText(null);
                if (errorNode.has("httpCode")) {
                    httpStatus = parseHttpStatus(errorNode.path("httpCode"));
                }
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("raw", response);
            metadata.put("nodeType", "execution");

            Instant startedAt = parseInstant(response.path("startedAt").asText(dataNode.path("startedAt").asText(null)))
                    .orElse(null);
            Instant finishedAt = parseInstant(response.path("stoppedAt").asText(dataNode.path("stoppedAt").asText(null)))
                    .orElse(null);

            nodes.add(new ExternalNodeExecution(
                    "execution",
                    executionExternalId,
                    workflowExternalId,
                    "Execution",
                    overall,
                    startedAt,
                    finishedAt,
                    null,
                    null,
                    null,
                    null,
                    httpStatus,
                    errorMessage,
                    errorCode,
                    "COMPLETE",
                    0,
                    metadata
            ));
        }

        return nodes;
    }

    /**
     * Backwards-compatible overload for single-run node mapping.
     */
    public static ExternalNodeExecution toExternal(
            JsonNode run, String nodeName, String executionExternalId, String workflowExternalId) {
        return toExternal(run, nodeName, executionExternalId, workflowExternalId, 0, 1, null, null);
    }

    /**
     * Converts a single run iteration of an n8n node into an {@link ExternalNodeExecution}.
     */
    public static ExternalNodeExecution toExternal(
            JsonNode run,
            String nodeName,
            String executionExternalId,
            String workflowExternalId,
            int runIndex,
            int totalRunsForNode,
            String lastNodeExecuted,
            JsonNode executionLevelError) {

        String externalId = totalRunsForNode > 1
                ? nodeName + ":" + runIndex
                : nodeName;

        String statusStr = run.path("executionStatus").asText("");
        ExecutionStatus status;
        if (statusStr.isBlank()) {
            if (run.path("error").isObject() && !run.path("error").isEmpty()) {
                status = ExecutionStatus.FAILED;
            } else {
                status = ExecutionStatus.SUCCEEDED;
            }
        } else {
            status = mapNodeStatus(statusStr);
        }

        Instant startedAt = parseInstantFromMs(run.path("startTime")).orElse(null);
        Instant finishedAt = parseInstantFromMs(run.path("startTime"))
                .map(t -> t.plusMillis(run.path("executionTime").asLong(0)))
                .orElse(null);

        Long inputSize = null;
        Long outputSize = run.has("outputSize") ? run.path("outputSize").asLong() : null;

        String errorCode = null;
        String errorMessage = null;
        Integer httpStatus = null;

        JsonNode errorNode = run.path("error");
        if (errorNode.isObject() && !errorNode.isEmpty()) {
            errorCode = errorNode.path("name").asText(null);
            errorMessage = errorNode.path("message").asText(null);
            if (errorNode.has("httpCode")) {
                httpStatus = parseHttpStatus(errorNode.path("httpCode"));
            }
        } else if (status == ExecutionStatus.FAILED) {
            if (executionLevelError != null && executionLevelError.isObject()
                    && (lastNodeExecuted == null || nodeName.equals(lastNodeExecuted))) {
                errorCode = executionLevelError.path("name").asText("ExecutionError");
                errorMessage = executionLevelError.path("message").asText(null);
                if (executionLevelError.has("httpCode")) {
                    httpStatus = parseHttpStatus(executionLevelError.path("httpCode"));
                }
            }
        }

        Integer itemCount = null;
        String dataCompleteness = "COMPLETE";
        Map<String, Object> outputSignature = null;

        JsonNode dataNode = run.path("data");
        if (dataNode.isMissingNode() || dataNode.isNull()) {
            dataCompleteness = "DATA_PRUNED";
            itemCount = null;
            outputSignature = null;
        } else if (dataNode.isObject()) {
            JsonNode main = dataNode.path("main");
            if (main.isArray()) {
                int total = 0;
                JsonNode sampleItem = null;
                for (JsonNode branch : main) {
                    if (branch.isArray()) {
                        total += branch.size();
                        if (sampleItem == null && branch.size() > 0) {
                            for (JsonNode item : branch) {
                                if (item.has("json") && item.path("json").isObject()) {
                                    sampleItem = item.path("json");
                                    break;
                                }
                            }
                        }
                    }
                }
                itemCount = total;
                if (total == 0) {
                    outputSignature = Map.of(
                            "hash", "empty",
                            "keys", List.of(),
                            "itemCount", 0,
                            "truncated", false
                    );
                } else if (sampleItem != null) {
                    outputSignature = extractStructuralSignature(sampleItem, total);
                } else {
                    outputSignature = Map.of(
                            "hash", "items_without_json",
                            "keys", List.of(),
                            "itemCount", total,
                            "truncated", false
                    );
                }
            } else if (dataNode.isEmpty()) {
                itemCount = 0;
                outputSignature = Map.of(
                        "hash", "empty",
                        "keys", List.of(),
                        "itemCount", 0,
                        "truncated", false
                );
            } else {
                itemCount = null;
                dataCompleteness = "UNSUPPORTED_DATA_FORMAT";
            }
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("raw", run);
        metadata.put("nodeType", categorizeNode(nodeName));

        // Subworkflow linking
        String subExecutionId = null;
        String subWorkflowId = null;
        JsonNode meta = run.path("metadata");
        if (meta.isObject()) {
            if (meta.has("subExecutionId")) {
                subExecutionId = meta.path("subExecutionId").asText(null);
            }
            if (meta.has("subWorkflowId")) {
                subWorkflowId = meta.path("subWorkflowId").asText(null);
            }
        }

        if (subExecutionId == null && dataNode.isObject() && dataNode.path("main").isArray()) {
            for (JsonNode branch : dataNode.path("main")) {
                if (branch.isArray()) {
                    for (JsonNode item : branch) {
                        JsonNode json = item.path("json");
                        if (json.has("childExecutionId")) {
                            subExecutionId = json.path("childExecutionId").asText(null);
                        }
                        if (json.has("subExecutionId") && subExecutionId == null) {
                            subExecutionId = json.path("subExecutionId").asText(null);
                        }
                        if (json.has("childWorkflowId")) {
                            subWorkflowId = json.path("childWorkflowId").asText(null);
                        }
                        if (json.has("subWorkflowId") && subWorkflowId == null) {
                            subWorkflowId = json.path("subWorkflowId").asText(null);
                        }
                        if (subExecutionId != null) {
                            break;
                        }
                    }
                }
                if (subExecutionId != null) {
                    break;
                }
            }
        }

        if (subExecutionId != null) {
            metadata.put("subExecutionId", subExecutionId);
        }
        if (subWorkflowId != null) {
            metadata.put("subWorkflowId", subWorkflowId);
        }

        return new ExternalNodeExecution(
                externalId,
                executionExternalId,
                workflowExternalId,
                nodeName,
                status,
                startedAt,
                finishedAt,
                inputSize,
                outputSize,
                itemCount,
                outputSignature,
                httpStatus,
                errorMessage,
                errorCode,
                dataCompleteness,
                runIndex,
                metadata);
    }

    /**
     * Extracts a stable, zero-PII schema signature from an item JSON object.
     */
    public static Map<String, Object> extractStructuralSignature(JsonNode json, int itemCount) {
        if (json == null || !json.isObject()) {
            return Map.of("hash", "empty", "keys", List.of(), "itemCount", itemCount, "truncated", false);
        }

        AtomicInteger keyCounter = new AtomicInteger(0);
        AtomicBoolean truncated = new AtomicBoolean(false);
        Map<String, Object> shape = extractShape(json, 0, keyCounter, truncated);

        List<String> sortedKeys = new ArrayList<>(shape.keySet());
        Collections.sort(sortedKeys);

        String canonicalString = buildCanonicalSchemaString(shape);
        String hash = sha256Hex(canonicalString);

        Map<String, Object> signature = new LinkedHashMap<>();
        signature.put("hash", hash);
        signature.put("shape", shape);
        signature.put("keys", sortedKeys);
        signature.put("itemCount", itemCount);
        signature.put("truncated", truncated.get());
        return signature;
    }

    private static Map<String, Object> extractShape(
            JsonNode obj, int depth, AtomicInteger keyCounter, AtomicBoolean truncated) {
        Map<String, Object> shape = new TreeMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
        while (fields.hasNext()) {
            if (keyCounter.incrementAndGet() > MAX_SCHEMA_KEYS) {
                truncated.set(true);
                break;
            }
            Map.Entry<String, JsonNode> entry = fields.next();
            String key = entry.getKey();
            JsonNode val = entry.getValue();

            if (val == null || val.isNull()) {
                shape.put(key, "null");
            } else if (val.isBoolean()) {
                shape.put(key, "boolean");
            } else if (val.isNumber()) {
                shape.put(key, "number");
            } else if (val.isTextual()) {
                shape.put(key, "string");
            } else if (val.isArray()) {
                shape.put(key, "array");
            } else if (val.isObject()) {
                if (depth < MAX_SCHEMA_DEPTH) {
                    shape.put(key, extractShape(val, depth + 1, keyCounter, truncated));
                } else {
                    truncated.set(true);
                    shape.put(key, "object");
                }
            } else {
                shape.put(key, "unknown");
            }
        }
        return shape;
    }

    @SuppressWarnings("unchecked")
    private static String buildCanonicalSchemaString(Map<String, Object> shape) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : shape.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append(e.getKey()).append(":");
            if (e.getValue() instanceof Map<?, ?> nested) {
                sb.append(buildCanonicalSchemaString((Map<String, Object>) nested));
            } else {
                sb.append(e.getValue());
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static String categorizeNode(String nodeName) {
        if (nodeName == null) {
            return "transform";
        }
        String lower = nodeName.toLowerCase();
        if (lower.contains("subworkflow") || lower.contains("execute workflow")) {
            return "subworkflow";
        }
        if (lower.contains("webhook") || lower.contains("trigger") || lower.contains("cron") || lower.contains("schedule")) {
            return "trigger";
        }
        if (lower.contains("respond to webhook") || lower.contains("send email") || lower.contains("slack") || lower.contains("terminal")) {
            return "terminal";
        }
        return "transform";
    }

    private static JsonNode findResultData(JsonNode response) {
        if (response == null || response.isNull()) {
            return null;
        }
        JsonNode data = response.path("data");
        if (data.path("resultData").isObject()) {
            return data.path("resultData");
        }
        if (data.path("data").path("resultData").isObject()) {
            return data.path("data").path("resultData");
        }
        if (response.path("resultData").isObject()) {
            return response.path("resultData");
        }
        return null;
    }

    private static ExecutionStatus mapNodeStatus(String n8nStatus) {
        return switch (n8nStatus.toLowerCase()) {
            case "success" -> ExecutionStatus.SUCCEEDED;
            case "error", "failed", "crashed" -> ExecutionStatus.FAILED;
            case "waiting" -> ExecutionStatus.WAITING;
            case "running", "new" -> ExecutionStatus.RUNNING;
            case "cancelled", "canceled" -> ExecutionStatus.CANCELED;
            default -> ExecutionStatus.FAILED;
        };
    }

    private static Integer parseHttpStatus(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        if (node.isTextual()) {
            try {
                return Integer.parseInt(node.asText().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Optional<Instant> parseInstantFromMs(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        long ms = node.asLong(0);
        if (ms <= 0) {
            return Optional.empty();
        }
        return Optional.of(Instant.ofEpochMilli(ms));
    }

    private static Optional<Instant> parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(iso));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }
}