package com.flowops.integration.provider.make;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowops.integration.provider.ExecutionStatus;
import com.flowops.integration.provider.ExternalExecution;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Make execution responses to normalized {@link ExternalExecution} records.
 * Pure static functions — no HTTP, no state, fully unit-testable.
 *
 * <p>Supports various Make REST API v2 execution envelopes:
 * <ul>
 *   <li>{@code { "executions": [ ... ] }}</li>
 *   <li>{@code { "scenarioLogs": [ ... ] }}</li>
 *   <li>{@code { "logs": [ ... ] }}</li>
 *   <li>{@code { "data": [ ... ] }}</li>
 *   <li>Direct JSON arrays {@code [ ... ]}</li>
 * </ul>
 *
 * <p>Handles both Make API v2 numeric status codes (1=Success, 2=Warning, 3=Error)
 * and string statuses (success, warning, failed, error, etc.).</p>
 */
public final class MakeExecutionMapper {

    private MakeExecutionMapper() {
    }

    public static List<ExternalExecution> toExternal(JsonNode response) {
        List<ExternalExecution> executions = new ArrayList<>();
        if (response == null || response.isNull()) {
            return executions;
        }

        JsonNode data = null;
        if (response.isArray()) {
            data = response;
        } else if (response.isObject()) {
            if (response.has("executions") && response.get("executions").isArray()) {
                data = response.get("executions");
            } else if (response.has("scenarioLogs") && response.get("scenarioLogs").isArray()) {
                data = response.get("scenarioLogs");
            } else if (response.has("logs") && response.get("logs").isArray()) {
                data = response.get("logs");
            } else if (response.has("data") && response.get("data").isArray()) {
                data = response.get("data");
            }
        }

        if (data != null && data.isArray()) {
            for (JsonNode execution : data) {
                if (execution == null || !execution.isObject()) {
                    continue;
                }
                ExternalExecution mapped = toExternalExecution(execution);
                if (mapped != null
                        && !mapped.externalId().isBlank()
                        && !mapped.workflowExternalId().isBlank()) {
                    executions.add(mapped);
                }
            }
        }
        return executions;
    }

    public static ExternalExecution toExternalExecution(JsonNode e) {
        if (e == null || !e.isObject()) {
            return null;
        }

        String externalId = extractStringId(e, "id", "executionId", "imtId");
        if (externalId == null || externalId.isBlank()) {
            return null;
        }

        String workflowExternalId = extractWorkflowId(e);
        if (workflowExternalId == null || workflowExternalId.isBlank()) {
            return null;
        }

        ExecutionStatus status = mapStatus(e.path("status").asText(""));
        Instant startedAt = parseInstant(firstNonNull(e.get("startedAt"), e.get("timestamp"), e.get("createdAt"), e.get("started")));
        Instant finishedAt = parseInstant(firstNonNull(e.get("finishedAt"), e.get("finished"), e.get("endedAt")));

        Map<String, Object> metadata = new LinkedHashMap<>();
        long duration = e.path("duration").asLong(0);
        if (duration <= 0 && startedAt != null && finishedAt != null) {
            duration = Math.max(0, finishedAt.toEpochMilli() - startedAt.toEpochMilli());
        }
        metadata.put("durationMs", duration);
        metadata.put("dataSizeBytes", e.path("dataSize").asLong(0));
        metadata.put("incomplete", e.path("incomplete").asBoolean(false));

        // Extract error details if present
        extractErrorDetails(e, metadata);

        metadata.put("raw", e);

        return new ExternalExecution(
                externalId, workflowExternalId, status, startedAt, finishedAt, metadata);
    }

    static ExecutionStatus mapStatus(String makeStatus) {
        if (makeStatus == null || makeStatus.isBlank()) {
            return ExecutionStatus.FAILED;
        }
        return switch (makeStatus.trim().toLowerCase()) {
            case "1", "success", "succeeded", "ok" -> ExecutionStatus.SUCCEEDED;
            case "2", "warning" -> ExecutionStatus.FAILED; // Warnings in Make often indicate partial/failed runs
            case "3", "error", "failed" -> ExecutionStatus.FAILED;
            case "running", "pending", "in_progress" -> ExecutionStatus.RUNNING;
            case "cancelled", "canceled", "aborted", "abort" -> ExecutionStatus.CANCELED;
            default -> ExecutionStatus.FAILED;
        };
    }

    private static String extractStringId(JsonNode node, String... candidateKeys) {
        for (String key : candidateKeys) {
            JsonNode v = node.get(key);
            if (v != null && !v.isNull()) {
                String text = v.asText();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    private static String extractWorkflowId(JsonNode e) {
        String id = extractStringId(e, "scenarioId", "scenario_id");
        if (id != null && !id.isBlank()) {
            return id;
        }
        JsonNode scenarioNode = e.get("scenario");
        if (scenarioNode != null) {
            if (scenarioNode.isObject()) {
                return extractStringId(scenarioNode, "id", "scenarioId");
            } else if (scenarioNode.isTextual() || scenarioNode.isNumber()) {
                return scenarioNode.asText();
            }
        }
        return null;
    }

    private static void extractErrorDetails(JsonNode e, Map<String, Object> metadata) {
        JsonNode errNode = e.get("error");
        if (errNode != null && !errNode.isNull()) {
            if (errNode.isObject()) {
                String msg = errNode.path("message").asText(null);
                String code = errNode.path("type").asText(errNode.path("code").asText(null));
                if (msg != null && !msg.isBlank()) {
                    metadata.put("errorMessage", msg);
                }
                if (code != null && !code.isBlank()) {
                    metadata.put("errorCode", code);
                }
            } else if (errNode.isTextual() && !errNode.asText().isBlank()) {
                metadata.put("errorMessage", errNode.asText());
            }
        }

        if (!metadata.containsKey("errorMessage")) {
            JsonNode msg = e.get("message");
            if (msg != null && msg.isTextual() && !msg.asText().isBlank()) {
                metadata.put("errorMessage", msg.asText());
            }
        }
    }

    private static JsonNode firstNonNull(JsonNode... nodes) {
        for (JsonNode n : nodes) {
            if (n != null && !n.isNull() && !n.isMissingNode()) {
                return n;
            }
        }
        return null;
    }

    private static Instant parseInstant(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            long val = node.asLong();
            if (val > 10_000_000_000L) {
                return Instant.ofEpochMilli(val);
            } else if (val > 0) {
                return Instant.ofEpochSecond(val);
            }
            return null;
        }
        String text = node.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (Exception ignored) {
            try {
                long epoch = Long.parseLong(text);
                return epoch > 10_000_000_000L ? Instant.ofEpochMilli(epoch) : Instant.ofEpochSecond(epoch);
            } catch (Exception ignored2) {
                return null;
            }
        }
    }
}