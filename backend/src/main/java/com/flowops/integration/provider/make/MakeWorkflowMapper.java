package com.flowops.integration.provider.make;

import com.fasterxml.jackson.databind.JsonNode;
import com.flowops.integration.provider.ExternalWorkflow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Make scenario responses to normalized {@link ExternalWorkflow} records.
 * Pure static functions — no HTTP, no state, fully unit-testable.
 */
public final class MakeWorkflowMapper {

    private MakeWorkflowMapper() {
    }

    public static List<ExternalWorkflow> toExternal(JsonNode response) {
        List<ExternalWorkflow> workflows = new ArrayList<>();
        if (response == null || response.isNull()) {
            return workflows;
        }

        JsonNode data = null;
        if (response.isArray()) {
            data = response;
        } else if (response.isObject()) {
            if (response.has("scenarios") && response.get("scenarios").isArray()) {
                data = response.get("scenarios");
            } else if (response.has("data") && response.get("data").isArray()) {
                data = response.get("data");
            }
        }

        if (data != null && data.isArray()) {
            for (JsonNode scenario : data) {
                if (scenario == null || !scenario.isObject()) {
                    continue;
                }
                ExternalWorkflow mapped = toExternalWorkflow(scenario);
                if (mapped != null && !mapped.externalId().isBlank()) {
                    workflows.add(mapped);
                }
            }
        }
        return workflows;
    }

    public static ExternalWorkflow toExternalWorkflow(JsonNode s) {
        if (s == null || !s.isObject()) {
            return null;
        }
        String externalId = s.path("id").isNumber()
                ? String.valueOf(s.path("id").asLong())
                : s.path("id").asText("");
        if (externalId.isBlank() && s.hasNonNull("scenarioId")) {
            externalId = s.path("scenarioId").asText("");
        }
        if (externalId.isBlank()) {
            return null;
        }

        String name = s.path("name").asText("Unnamed Scenario");
        String status = s.path("status").asText("UNKNOWN");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("folderId", s.path("folderId").asText(""));
        metadata.put("status", status);
        metadata.put("createdAt", s.path("createdAt").asText(""));
        metadata.put("updatedAt", s.path("updatedAt").asText(""));
        metadata.put("raw", s);
        return new ExternalWorkflow(externalId, name, status, metadata);
    }
}